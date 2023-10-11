package zio.interop.reactivestreams

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.reactivestreams.Processor
import zio._
import zio.Unsafe._
import zio.internal.RingBuffer
import zio.stream._

import java.util.concurrent.atomic.AtomicReference
import zio.stream.internal.AsyncInputConsumer
import zio.stream.internal.AsyncInputProducer
import zio.UIO
import java.util.concurrent.atomic.AtomicBoolean

object Adapters {

  def streamToPublisher[R, E <: Throwable, O](
    stream: => ZStream[R, E, O]
  )(implicit trace: Trace): ZIO[R, Nothing, Publisher[O]] =
    ZIO.runtime[R].map { runtime => subscriber =>
      if (subscriber == null) {
        throw new NullPointerException("Subscriber must not be null.")
      } else
        unsafe { implicit unsafe =>
          val subscription = new SubscriptionProducer[O](subscriber)
          subscriber.onSubscribe(subscription)
          runtime.unsafe.fork(
            (stream.toChannel >>> ZChannel.fromZIO(subscription.awaitCancellation).embedInput(subscription)).runDrain
          )
          ()
        }
    }

  def subscriberToSink[E <: Throwable, I](
    subscriber: => Subscriber[I]
  )(implicit trace: Trace): ZIO[Scope, Nothing, (E => UIO[Unit], ZSink[Any, Nothing, I, I, Unit])] =
    ZIO.suspendSucceed {
      unsafe { implicit unsafe =>
        val subscription = new SubscriptionProducer[I](subscriber)

        def reader: ZChannel[Any, ZNothing, Chunk[I], Any, Nothing, Chunk[I], Unit] = ZChannel.readWith(
          i => ZChannel.fromZIO(subscription.emit(i)) *> reader,
          _ => ???, // impossible
          d => ZChannel.fromZIO(subscription.done(d)) *> ZChannel.succeed(())
        )

        for {
          _ <- ZIO.acquireRelease(ZIO.succeed(subscriber.onSubscribe(subscription)))(_ =>
                 ZIO.succeed(subscription.cancel())
               )
        } yield ((e: E) => subscription.error(Cause.fail(e)).sandbox.ignore, ZSink.fromChannel(reader))
      }
    }

  def publisherToStream[O](
    publisher: => Publisher[O],
    bufferSize: => Int
  )(implicit trace: Trace): ZStream[Any, Throwable, O] = ZStream.unwrapScoped {
    val subscriber = new SubscriberConsumer[O](bufferSize)(unsafe)

    for {
      _ <-
        ZIO.acquireRelease(ZIO.succeed(publisher.subscribe(subscriber)))(_ => subscriber.cancelSubscription.forkDaemon)
    } yield ZStream.fromChannel(ZChannel.fromInput(subscriber))
  }

  def sinkToSubscriber[R, I, L, Z](
    sink: => ZSink[R, Throwable, I, L, Z],
    bufferSize: => Int
  )(implicit trace: Trace): ZIO[R with Scope, Throwable, (Subscriber[I], IO[Throwable, Z])] = {
    val subscriber = new SubscriberConsumer[I](bufferSize)(unsafe)
    for {
      _         <- ZIO.addFinalizer(subscriber.cancelSubscription.forkDaemon)
      sinkFiber <- (ZChannel.fromInput(subscriber) pipeToOrFail sink.channel).runDrain.forkScoped
    } yield (subscriber, sinkFiber.join)
  }

  def processorToPipeline[I, O](
    processor: Processor[I, O],
    bufferSize: Int = 16
  )(implicit trace: Trace): ZPipeline[Any, Throwable, I, O] = ZPipeline.unwrapScoped {
    val subscription = new SubscriptionProducer[I](processor)(unsafe)
    val subscriber   = new SubscriberConsumer[O](bufferSize)(unsafe)

    for {
      _ <-
        ZIO.acquireRelease(ZIO.succeed(processor.subscribe(subscriber)))(_ => subscriber.cancelSubscription.forkDaemon)
      _ <- ZIO.acquireRelease(ZIO.succeed(processor.onSubscribe(subscription)))(_ => ZIO.succeed(subscription.cancel()))
    } yield ZPipeline.fromChannel(ZChannel.fromInput(subscriber).embedInput(subscription))
  }

  def pipelineToProcessor[R <: Scope, I, O](
    pipeline: ZPipeline[R, Throwable, I, O],
    bufferSize: Int = 16
  )(implicit trace: Trace): ZIO[R, Nothing, Processor[I, O]] =
    for {
      runtime   <- ZIO.runtime[R]
      subscriber = new SubscriberConsumer[I](bufferSize)(unsafe)
    } yield new Processor[I, O] {
      def onSubscribe(s: Subscription): Unit = subscriber.onSubscribe(s)

      def onNext(t: I): Unit = subscriber.onNext(t)

      def onError(t: Throwable): Unit = subscriber.onError(t)

      def onComplete(): Unit = subscriber.onComplete()

      def subscribe(s: Subscriber[_ >: O]): Unit = {
        val subscription = new SubscriptionProducer[O](s)(unsafe)
        s.onSubscribe(subscription)
        unsafe { implicit u =>
          runtime.unsafe.fork {
            ((ZChannel.fromInput(subscriber) pipeToOrFail pipeline.toChannel) >>> ZChannel
              .fromZIO(subscription.awaitCancellation *> subscriber.cancelSubscription)
              .embedInput(subscription)).runDrain
          }
          ()
        }
      }
    }

  private class SubscriptionProducer[A](sub: Subscriber[_ >: A])(implicit unsafe: Unsafe)
      extends Subscription
      with AsyncInputProducer[Throwable, Chunk[A], Any] {
    import SubscriptionProducer.State

    private val state: AtomicReference[State[A]] = new AtomicReference(State.initial[A])
    private val canceled: Promise[Nothing, Unit] = Promise.unsafe.make(FiberId.None)

    val awaitCancellation: UIO[Unit] = canceled.await

    def request(n: Long): Unit =
      if (n <= 0) sub.onError(new IllegalArgumentException("non-positive subscription request"))
      else {
        state.getAndUpdate {
          case State.Running(demand) => State.Running(demand + n)
          case State.Waiting(_)      => State.Running(n)
          case other                 => other
        } match {
          case State.Waiting(resume) => resume.unsafe.done(ZIO.unit)
          case _                     => ()
        }
      }

    def cancel(): Unit = {
      state.getAndSet(State.Cancelled) match {
        case State.Waiting(resume) => resume.unsafe.done(ZIO.interrupt)
        case _                     => ()
      }
      canceled.unsafe.done(ZIO.unit)
    }

    def emit(el: Chunk[A])(implicit trace: zio.Trace): UIO[Any] = ZIO.suspendSucceed {
      if (el.isEmpty) ZIO.unit
      else
        ZIO.suspendSucceed {
          state.getAndUpdate {
            case State.Running(demand) =>
              if (demand > el.size)
                State.Running(demand - el.size)
              else
                State.Waiting(Promise.unsafe.make[Nothing, Unit](FiberId.None))
            case other => other
          } match {
            case State.Waiting(resume) =>
              resume.await *> emit(el)
            case State.Running(demand) =>
              if (demand > el.size)
                ZIO.succeed(el.foreach(sub.onNext(_)))
              else
                ZIO.succeed(el.take(demand.toInt).foreach(sub.onNext(_))) *> emit(el.drop(demand.toInt))
            case State.Cancelled =>
              ZIO.interrupt
          }
        }
    }

    def done(a: Any)(implicit trace: zio.Trace): UIO[Any] = ZIO.suspendSucceed {
      state.getAndSet(State.Cancelled) match {
        case State.Running(_)      => ZIO.succeed(sub.onComplete()) *> canceled.succeed(())
        case State.Cancelled       => ZIO.interrupt
        case State.Waiting(resume) => ZIO.succeed(sub.onComplete()) *> resume.interrupt *> canceled.succeed(())
      }
    }

    def error(cause: Cause[Throwable])(implicit trace: zio.Trace): UIO[Any] = ZIO.suspendSucceed {
      state.getAndSet(State.Cancelled) match {
        case State.Running(_) =>
          ZIO.succeed {
            cause.failureOrCause.fold(
              sub.onError,
              c => sub.onError(new FiberFailure(c))
            )
          } *> canceled.succeed(())
        case State.Cancelled => ZIO.interrupt
        case State.Waiting(resume) =>
          ZIO.succeed {
            cause.failureOrCause.fold(
              sub.onError,
              c => sub.onError(new FiberFailure(c))
            )
          } *> resume.interrupt *> canceled.succeed(())
      }
    }

    def awaitRead(implicit trace: zio.Trace): UIO[Any] = ZIO.unit
  }

  private object SubscriptionProducer {
    sealed trait State[+A]
    object State {
      def initial[A](implicit unsafe: Unsafe): State[A] = Waiting(Promise.unsafe.make[Nothing, Unit](FiberId.None))

      final case class Waiting(resume: Promise[Nothing, Unit]) extends State[Nothing]
      final case class Running(demand: Long)                   extends State[Nothing]
      case object Cancelled                                    extends State[Nothing]
    }
  }

  private class SubscriberConsumer[A](capacity: Int)(implicit unsafe: Unsafe)
      extends Subscriber[A]
      with AsyncInputConsumer[Throwable, Chunk[A], Any] {
    import SubscriberConsumer.State

    private val subscription: Promise[Nothing, Subscription] = Promise.unsafe.make(FiberId.None)
    private val buffer: RingBuffer[A]                        = RingBuffer(capacity)
    private val state: AtomicReference[State]                = new AtomicReference(State.Drained)
    private val isSubscribed: AtomicBoolean                  = new AtomicBoolean(false)

    def onSubscribe(s: Subscription): Unit =
      if (!isSubscribed.compareAndSet(false, true)) {
        s.cancel()
      } else {
        subscription.unsafe.done(ZIO.succeedNow(s))
        s.request(buffer.capacity.toLong)
      }

    def onNext(t: A): Unit =
      if (t == null) {
        throw new NullPointerException("t was null in onNext")
      } else {
        buffer.offer(t)
        state.getAndUpdate {
          case State.Drained    => State.Full
          case State.Waiting(_) => State.Full
          case other            => other
        } match {
          case State.Waiting(promise) => promise.unsafe.done(ZIO.unit)
          case _                      => ()
        }
      }

    def onError(t: Throwable): Unit =
      if (t == null) {
        throw new NullPointerException("t was null in onError")
      } else {
        state.getAndSet(State.Failed(t)) match {
          case State.Waiting(promise) => promise.unsafe.done(ZIO.unit)
          case _                      => ()
        }
      }

    def onComplete(): Unit =
      state.getAndSet(State.Ended) match {
        case State.Waiting(promise) => promise.unsafe.done(ZIO.unit)
        case _                      => ()
      }

    def cancelSubscription: UIO[Unit] =
      subscription.await.tap(s => ZIO.succeed(s.cancel())).unit

    def takeWith[B](onError: Cause[Throwable] => B, onElement: Chunk[A] => B, onDone: Any => B)(implicit
      trace: zio.Trace
    ): UIO[B] = subscription.await.flatMap { sub =>
      ZIO.suspendSucceed {
        state.updateAndGet {
          case State.Drained => State.Waiting(Promise.unsafe.make[Nothing, Unit](FiberId.None))
          case State.Full    => State.Drained
          case other         => other
        } match {
          case State.Drained =>
            val data     = buffer.pollUpTo(buffer.capacity)
            val dataSize = data.size
            if (dataSize > 0) {
              sub.request(data.size.toLong)
              ZIO.succeedNow(onElement(data))
            } else {
              ZIO.succeedNow(onElement(Chunk.empty))
            }
          case State.Full             => ??? // impossible
          case State.Waiting(promise) => promise.await *> takeWith(onError, onElement, onDone)
          case State.Failed(t)        =>
            // drain remaining data before failing
            val data = buffer.pollUpTo(buffer.capacity)
            if (data.nonEmpty) ZIO.succeedNow(onElement(data)) else ZIO.succeedNow(onError(Cause.fail(t)))
          case State.Ended =>
            // drain remaining data before failing
            val data = buffer.pollUpTo(buffer.capacity)
            if (data.nonEmpty) ZIO.succeedNow(onElement(data)) else ZIO.succeedNow(onDone(()))
        }
      }
    }
  }

  private object SubscriberConsumer {

    sealed trait State

    object State {
      case object Drained                                       extends State
      case object Full                                          extends State
      final case class Waiting(promise: Promise[Nothing, Unit]) extends State
      final case class Failed(cause: Throwable)                 extends State
      case object Ended                                         extends State
    }

  }

}
