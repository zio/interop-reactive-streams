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
import java.util.concurrent.atomic.AtomicBoolean
import zio.UIO
import scala.util.control.NoStackTrace

object Adapters {

  def streamToPublisher[R, E <: Throwable, O](
    stream: => ZStream[R, E, O]
  )(implicit trace: Trace): ZIO[R, Nothing, Publisher[O]] = ZIO.runtime[R].map { runtime => subscriber =>
    if (subscriber == null) {
      throw new NullPointerException("Subscriber must not be null.")
    } else
      unsafe { implicit unsafe =>
        val subscription = new SubscriptionProducer[O](subscriber)
        subscriber.onSubscribe(subscription)
        runtime.unsafe.fork(
          (stream.toChannel >>> ZChannel.fromZIO(subscription.awaitCompletion).embedInput(subscription)).runDrain
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

        def reader: ZChannel[Any, ZNothing, Chunk[I], Any, Nothing, Chunk[I], Unit] = ZChannel.readWithCause(
          i => ZChannel.fromZIO(subscription.emit(i)) *> reader,
          e => ZChannel.failCause(e),
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
  )(implicit trace: Trace): ZStream[Any, Throwable, O] = publisherToChannel(publisher, bufferSize).toStream

  def sinkToSubscriber[R, I, L, Z](
    sink: => ZSink[R, Throwable, I, L, Z],
    bufferSize: => Int
  )(implicit trace: Trace): ZIO[R with Scope, Throwable, (Subscriber[I], IO[Throwable, Z])] = {
    def promChannel(prom: Promise[Throwable, Z]): ZChannel[R, Throwable, Chunk[L], Z, Any, Any, Any] =
      ZChannel.readWithCause(
        ZChannel.write(_) *> promChannel(prom),
        e => ZChannel.fromZIO(prom.failCause(e)) *> ZChannel.failCause(e),
        d => ZChannel.fromZIO(prom.succeed(d)) *> ZChannel.succeed(d)
      )

    for {
      prom <- Promise.make[Throwable, Z]
      subscriber <- channelToSubscriber(
                      (ZChannel.identity[Throwable, Chunk[I], Any] pipeToOrFail sink.channel) >>> promChannel(prom),
                      bufferSize
                    )
    } yield (subscriber, prom.await)
  }

  /** Upstream errors will not be passed to the processor. If you want errors to be passed, convert the processor to a
    * channel instead.
    */
  def processorToPipeline[I, O](
    processor: Processor[I, O],
    bufferSize: Int = 16
  )(implicit trace: Trace): ZPipeline[Any, Throwable, I, O] = ZPipeline.unwrapScoped {
    val subscription = new SubscriptionProducer[I](processor)(unsafe)
    val subscriber   = new SubscriberConsumer[O](bufferSize)(unsafe)
    val passthrough  = new PassthroughAsyncInput(subscription, subscriber)

    for {
      _ <-
        ZIO.acquireRelease(ZIO.succeed(processor.subscribe(subscriber)))(_ => subscriber.cancelSubscription)
      _ <- ZIO.acquireRelease(ZIO.succeed(processor.onSubscribe(subscription)))(_ => ZIO.succeed(subscription.cancel()))
    } yield ZPipeline.fromChannel(ZChannel.fromInput(passthrough).embedInput(passthrough))
  }

  def pipelineToProcessor[R <: Scope, I, O](
    pipeline: ZPipeline[R, Throwable, I, O],
    bufferSize: Int = 16
  )(implicit trace: Trace): ZIO[R, Nothing, Processor[I, O]] =
    channelToProcessor(ZChannel.identity pipeToOrFail pipeline.channel, bufferSize)

  def channelToPublisher[R <: Scope, O](
    channel: ZChannel[R, Any, Any, Any, Throwable, Chunk[O], Any]
  ): ZIO[R, Nothing, Publisher[O]] = ZIO.runtime[R].map { runtime =>
    new Publisher[O] {
      def subscribe(subscriber: Subscriber[_ >: O]): Unit =
        if (subscriber == null) {
          throw new NullPointerException("Subscriber must not be null.")
        } else {
          val subscription = new SubscriptionProducer[O](subscriber)(unsafe)
          unsafe { implicit u =>
            runtime.unsafe.run {
              for {
                _ <- ZIO.acquireRelease(ZIO.succeed(subscriber.onSubscribe(subscription)))(_ =>
                       ZIO.succeed(subscription.cancel())
                     )
                _ <- (channel >>> ZChannel
                       .fromZIO(subscription.awaitCompletion)
                       .embedInput(subscription)).runDrain.forkScoped
              } yield ()
            }.getOrThrow()
          }
        }
    }
  }

  def channelToSubscriber[R <: Scope, I](
    channel: ZChannel[R, Throwable, Chunk[I], Any, Any, Any, Any],
    bufferSize: Int = 16
  ): ZIO[R, Nothing, Subscriber[I]] = ZIO.suspendSucceed {
    val subscriber = new SubscriberConsumer[I](bufferSize)(unsafe)
    for {
      _ <- ZIO.addFinalizer(subscriber.cancelSubscription)
      _ <- (ZChannel.fromInput(subscriber) >>> channel).runDrain.forkScoped
    } yield subscriber
  }

  def channelToProcessor[R <: Scope, I, O](
    channel: ZChannel[R, Throwable, Chunk[I], Any, Throwable, Chunk[O], Any],
    bufferSize: Int = 16
  ): ZIO[R, Nothing, Processor[I, O]] =
    for {
      runtime   <- ZIO.runtime[R]
      subscriber = new SubscriberConsumer[I](bufferSize)(unsafe)
      _         <- ZIO.addFinalizer(subscriber.cancelSubscription)
    } yield new Processor[I, O] {
      def onSubscribe(s: Subscription): Unit = subscriber.onSubscribe(s)

      def onNext(t: I): Unit = subscriber.onNext(t)

      def onError(t: Throwable): Unit = subscriber.onError(t)

      def onComplete(): Unit = subscriber.onComplete()

      def subscribe(s: Subscriber[_ >: O]): Unit = {
        val subscription = new SubscriptionProducer[O](s)(unsafe)
        unsafe { implicit u =>
          runtime.unsafe.run {
            for {
              finalizerRef <- Ref.make(ZIO.unit)
              _            <- ZIO.addFinalizer(finalizerRef.get.flatten)
              _ <- (ZIO.succeed(s.onSubscribe(subscription)) *> finalizerRef.set(
                     ZIO.succeed(subscription.cancel())
                   )).uninterruptible
              _ <-
                (ZChannel.fromInput(subscriber) >>> channel >>> ZChannel
                  .fromZIO(subscription.awaitCompletion *> finalizerRef.set(ZIO.unit) *> subscriber.cancelSubscription)
                  .embedInput(subscription)).runDrain.forkScoped
            } yield ()
          }.getOrThrow()
        }
      }
    }

  def publisherToChannel[O](
    publisher: Publisher[O],
    bufferSize: Int = 16
  )(implicit trace: Trace): ZChannel[Any, Any, Any, Any, Throwable, Chunk[O], Any] = ZChannel.unwrapScoped[Any] {
    val subscriber = new SubscriberConsumer[O](bufferSize)(unsafe)

    for {
      _ <-
        ZIO.acquireRelease(ZIO.succeed(publisher.subscribe(subscriber)))(_ => subscriber.cancelSubscription)
    } yield ZChannel.fromInput(subscriber)
  }

  def subscriberToChannel[I](
    consumer: Subscriber[I]
  )(implicit trace: Trace): ZChannel[Any, Throwable, Chunk[I], Any, Any, Any, Any] = ZChannel.unwrapScoped[Any] {
    val subscription = new SubscriptionProducer[I](consumer)(unsafe)

    for {
      _ <- ZIO.acquireRelease(ZIO.succeed(consumer.onSubscribe(subscription)))(_ => ZIO.succeed(subscription.cancel()))
    } yield ZChannel.fromZIO(subscription.awaitCompletion).embedInput(subscription)
  }

  def processorToChannel[I, O](
    processor: Processor[I, O],
    bufferSize: Int = 16
  )(implicit trace: Trace): ZChannel[Any, Throwable, Chunk[I], Any, Throwable, Chunk[O], Any] = ZChannel.unwrapScoped {
    val subscription = new SubscriptionProducer[I](processor)(unsafe)
    val subscriber   = new SubscriberConsumer[O](bufferSize)(unsafe)

    for {
      _ <-
        ZIO.acquireRelease(ZIO.succeed(processor.subscribe(subscriber)))(_ => subscriber.cancelSubscription)
      _ <- ZIO.acquireRelease(ZIO.succeed(processor.onSubscribe(subscription)))(_ => ZIO.succeed(subscription.cancel()))
    } yield ZChannel.fromInput(subscriber).embedInput(subscription)
  }

  private class SubscriptionProducer[A](sub: Subscriber[_ >: A])(implicit unsafe: Unsafe)
      extends Subscription
      with AsyncInputProducer[Throwable, Chunk[A], Any] {
    import SubscriptionProducer.State

    private val state: AtomicReference[State[A]]  = new AtomicReference(State.initial[A])
    private val completed: Promise[Nothing, Unit] = Promise.unsafe.make(FiberId.None)

    val awaitCompletion: UIO[Unit] = completed.await

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
      completed.unsafe.done(ZIO.unit)
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
        case State.Running(_)      => ZIO.succeed(sub.onComplete()) *> completed.succeed(())
        case State.Cancelled       => ZIO.interrupt
        case State.Waiting(resume) => ZIO.succeed(sub.onComplete()) *> resume.interrupt *> completed.succeed(())
      }
    }

    def error(cause: Cause[Throwable])(implicit trace: zio.Trace): UIO[Any] = ZIO.suspendSucceed {
      state.getAndSet(State.Cancelled) match {
        case State.Running(_) =>
          ZIO.succeed {
            cause.failureOrCause.fold(
              sub.onError,
              c => sub.onError(UpstreamDefect(c))
            )
          } *> completed.succeed(())
        case State.Cancelled => ZIO.interrupt
        case State.Waiting(resume) =>
          ZIO.succeed {
            cause.failureOrCause.fold(
              sub.onError,
              c => sub.onError(UpstreamDefect(c))
            )
          } *> resume.interrupt *> completed.succeed(())
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
    private val isSubscribedOrCanceled: AtomicBoolean        = new AtomicBoolean(false)

    def onSubscribe(s: Subscription): Unit =
      if (!isSubscribedOrCanceled.compareAndSet(false, true)) {
        s.cancel()
      } else {
        subscription.unsafe.done(ZIO.succeedNow(s))
        s.request(buffer.capacity.toLong)
      }

    def onNext(t: A): Unit =
      if (t == null) {
        throw new NullPointerException("t was null in onNext")
      } else if (!buffer.offer(t)) {
        throw new IllegalStateException("buffer is full")
      } else {
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
      ZIO.succeed(isSubscribedOrCanceled.set(true)) *>
        subscription.poll.flatMap(ZIO.foreachDiscard(_)(_.map(_.cancel()))) *>
        subscription.interrupt.unit *>
        ZIO.succeed(state.getAndSet(State.Canceled) match {
          case State.Waiting(promise) => promise.unsafe.done(ZIO.unit)
          case _                      => ()
        })

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
            val dataSize = data.size.toLong
            if (dataSize > 0) {
              sub.request(data.size.toLong)
              ZIO.succeedNow(onElement(data))
            } else {
              ZIO.succeedNow(onElement(Chunk.empty))
            }

          case State.Full => ??? // impossible

          case State.Waiting(promise) =>
            promise.await *> takeWith(onError, onElement, onDone)

          case State.Failed(t) =>
            // drain remaining data before failing
            val data = buffer.pollUpTo(buffer.capacity)
            if (data.nonEmpty) ZIO.succeedNow(onElement(data))
            else {
              t match {
                case UpstreamDefect(cause) => ZIO.succeedNow(onError(cause))
                case err                   => ZIO.succeedNow(onError(Cause.fail(t)))
              }
            }
          case State.Ended =>
            // drain remaining data before failing
            val data = buffer.pollUpTo(buffer.capacity)
            if (data.nonEmpty) ZIO.succeedNow(onElement(data)) else ZIO.succeedNow(onDone(()))

          case State.Canceled =>
            ZIO.interrupt
        }
      }
    }
  }

  private object SubscriberConsumer {

    sealed trait State

    object State {
      final case class Waiting(promise: Promise[Nothing, Unit]) extends State
      case object Drained                                       extends State
      case object Full                                          extends State
      final case class Failed(cause: Throwable)                 extends State
      case object Ended                                         extends State
      case object Canceled                                      extends State

    }

  }

  private final case class UpstreamDefect(cause: Cause[Nothing]) extends NoStackTrace {
    override def getMessage(): String = s"Upsteam defect: ${cause.prettyPrint}"
  }

  class PassthroughAsyncInput[I, O](
    producer: AsyncInputProducer[Nothing, I, Any],
    consumer: AsyncInputConsumer[Throwable, O, Any]
  ) extends AsyncInputProducer[Throwable, I, Any]
      with AsyncInputConsumer[Throwable, O, Any] {
    private val error: Promise[Nothing, Cause[Throwable]] = unsafe(implicit u => Promise.unsafe.make(FiberId.None))

    def takeWith[A](onError: Cause[Throwable] => A, onElement: O => A, onDone: Any => A)(implicit
      trace: zio.Trace
    ): UIO[A] =
      consumer.takeWith(onError, onElement, onDone) race error.await.map(onError)

    def emit(el: I)(implicit trace: zio.Trace): UIO[Any] = producer.emit(el)

    def done(a: Any)(implicit trace: zio.Trace): UIO[Any] = producer.done(a)

    def error(cause: Cause[Throwable])(implicit trace: zio.Trace): UIO[Any] = error.succeed(cause)
    def awaitRead(implicit trace: zio.Trace): UIO[Any]                      = producer.awaitRead

  }
}
