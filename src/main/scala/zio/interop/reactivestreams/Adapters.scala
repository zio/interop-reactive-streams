package zio.interop.reactivestreams

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import zio._
import zio.internal.RingBuffer
import zio.stream.ZSink
import zio.stream.ZStream
import zio.stream.ZStream.Pull

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object Adapters {

  def streamToPublisher[R, E <: Throwable, O](
    stream: => ZStream[R, E, O]
  )(implicit trace: ZTraceElement): ZIO[R, Nothing, Publisher[O]] =
    ZIO.runtime.map { runtime => subscriber =>
      if (subscriber == null) {
        throw new NullPointerException("Subscriber must not be null.")
      } else {
        val subscription = new DemandTrackingSubscription(subscriber)
        runtime.unsafeRunAsync(
          for {
            _ <- UIO(subscriber.onSubscribe(subscription))
            _ <- stream
                   .run(demandUnfoldSink(subscriber, subscription))
                   .catchAll(e => UIO(subscriber.onError(e)))
                   .forkDaemon
          } yield ()
        )
      }
    }

  def subscriberToSink[E <: Throwable, I](
    subscriber: => Subscriber[I]
  )(implicit trace: ZTraceElement): ZManaged[Any, Nothing, (Promise[E, Nothing], ZSink[Any, Nothing, I, I, Unit])] = {
    val sub = subscriber
    for {
      error       <- Promise.make[E, Nothing].toManaged
      subscription = new DemandTrackingSubscription(sub)
      _           <- UIO(sub.onSubscribe(subscription)).toManaged
      _           <- error.await.catchAll(t => UIO(sub.onError(t))).toManaged.fork
    } yield (error, demandUnfoldSink(sub, subscription))
  }

  def publisherToStream[O](
    publisher: => Publisher[O],
    bufferSize: => Int
  )(implicit trace: ZTraceElement): ZStream[Any, Throwable, O] = {
    val pullOrFail =
      for {
        subscriberP    <- makeSubscriber[O](bufferSize)
        (subscriber, p) = subscriberP
        _              <- ZManaged.finalizer(UIO(subscriber.interrupt()))
        _              <- ZManaged.succeed(publisher.subscribe(subscriber))
        subQ           <- p.await.toManaged
        (sub, q)        = subQ
        process        <- process(sub, q, () => subscriber.await(), () => subscriber.isDone)
      } yield process
    val pull = pullOrFail.catchAll(e => ZManaged.succeed(Pull.fail(e)))
    ZStream(pull)
  }

  def sinkToSubscriber[R, I, L, Z](
    sink: => ZSink[R, Throwable, I, L, Z],
    bufferSize: => Int
  )(implicit trace: ZTraceElement): ZManaged[R, Throwable, (Subscriber[I], IO[Throwable, Z])] =
    for {
      subscriberP    <- makeSubscriber[I](bufferSize)
      (subscriber, p) = subscriberP
      pull = p.await.toManaged.flatMap { case (subscription, q) =>
               process(subscription, q, () => subscriber.await(), () => subscriber.isDone, bufferSize)
             }
               .catchAll(e => ZManaged.succeedNow(Pull.fail(e)))
      fiber <- ZStream(pull).run(sink).toManaged.fork
    } yield (subscriber, fiber.join)

  private def process[A](
    sub: Subscription,
    q: RingBuffer[A],
    await: () => IO[Option[Throwable], Unit],
    isDone: () => Boolean,
    maxChunkSize: Int = Int.MaxValue
  ): ZManaged[Any, Nothing, ZIO[Any, Option[Throwable], Chunk[A]]] =
    for {
      _            <- ZManaged.succeed(sub.request(q.capacity.toLong))
      requestedRef <- Ref.makeManaged(q.capacity.toLong) // TODO: maybe turn into unfold?
    } yield {
      def pull: Pull[Any, Throwable, A] =
        for {
          requested <- requestedRef.get
          pollSize   = Math.min(requested, maxChunkSize.toLong).toInt
          chunk     <- UIO(q.pollUpTo(pollSize))
          r <-
            if (chunk.isEmpty)
              await() *> pull
            else
              (if (chunk.size == pollSize && !isDone())
                 UIO(sub.request(q.capacity.toLong)) *> requestedRef.set(q.capacity.toLong)
               else requestedRef.set(requested - chunk.size)) *>
                Pull.emit(chunk)
        } yield r

      pull
    }

  private trait InterruptibleSubscriber[A] extends Subscriber[A] {
    def interrupt(): Unit
    def await(): IO[Option[Throwable], Unit]
    def isDone: Boolean
  }

  private def makeSubscriber[A](
    capacity: Int
  ): UManaged[
    (
      InterruptibleSubscriber[A],
      Promise[Throwable, (Subscription, RingBuffer[A])]
    )
  ] =
    for {
      q <- ZManaged.succeed(RingBuffer[A](capacity))
      p <-
        Promise
          .make[Throwable, (Subscription, RingBuffer[A])]
          .toManagedWith(
            _.poll.flatMap(_.fold(UIO.unit)(_.foldZIO(_ => UIO.unit, { case (sub, _) => UIO(sub.cancel()) })))
          )
    } yield {
      val subscriber =
        new InterruptibleSubscriber[A] {

          val isSubscribedOrInterrupted = new AtomicBoolean
          @volatile
          var done: Option[Option[Throwable]] = None
          @volatile
          var toNotify: Option[Promise[Option[Throwable], Unit]] = None

          override def interrupt(): Unit = isSubscribedOrInterrupted.set(true)

          override def await(): IO[Option[Throwable], Unit] =
            done match {
              case Some(value) => IO.fail(value)
              case None =>
                val p = Promise.unsafeMake[Option[Throwable], Unit](FiberId.None)
                toNotify = Some(p)
                // An element has arrived in the meantime, we do not need to start waiting.
                if (!q.isEmpty()) {
                  toNotify = None
                  IO.unit
                } else
                  done.fold(p.await) { e =>
                    // The producer has canceled or errored in the meantime.
                    toNotify = None
                    IO.fail(e)
                  }
            }

          override def isDone: Boolean = done.isDefined

          override def onSubscribe(s: Subscription): Unit =
            if (s == null) {
              val e = new NullPointerException("s was null in onSubscribe")
              p.unsafeDone(IO.fail(e))
              throw e
            } else {
              val shouldCancel = isSubscribedOrInterrupted.getAndSet(true)
              if (shouldCancel)
                s.cancel()
              else
                p.unsafeDone(UIO.succeedNow((s, q)))
            }

          override def onNext(t: A): Unit =
            if (t == null) {
              failNPE("t was null in onNext")
            } else {
              q.offer(t)
              toNotify.foreach(_.unsafeDone(IO.unit))
            }

          override def onError(e: Throwable): Unit =
            if (e == null)
              failNPE("t was null in onError")
            else
              fail(e)

          override def onComplete(): Unit = {
            done = Some(None)
            toNotify.foreach(_.unsafeDone(IO.fail(None)))
          }

          private def failNPE(msg: String) = {
            val e = new NullPointerException(msg)
            fail(e)
            throw e
          }

          private def fail(e: Throwable) = {
            done = Some(Some(e))
            toNotify.foreach(_.unsafeDone(IO.fail(Some(e))))
          }

        }

      (subscriber, p)
    }

  private def demandUnfoldSink[I](
    subscriber: Subscriber[I],
    subscription: DemandTrackingSubscription
  ): ZSink[Any, Nothing, I, I, Unit] =
    ZSink
      .foldChunksZIO[Any, Nothing, I, Boolean](true)(identity) { (_, chunk) =>
        IO
          .iterate(chunk)(!_.isEmpty) { chunk =>
            subscription
              .offer(chunk.size)
              .flatMap { acceptedCount =>
                UIO.foreach(chunk.take(acceptedCount))(a => UIO(subscriber.onNext(a))).as(chunk.drop(acceptedCount))
              }
          }
          .fold(
            _ => false, // canceled
            _ => true
          )
      }
      .map(_ => if (!subscription.isCanceled) subscriber.onComplete())

  private class DemandTrackingSubscription(subscriber: Subscriber[_]) extends Subscription {

    private case class State(
      requestedCount: Long, // -1 when cancelled
      toNotify: Option[(Int, Promise[Unit, Int])]
    )

    private val initial                                 = State(0L, None)
    private val canceled                                = State(-1, None)
    private def requested(n: Long)                      = State(n, None)
    private def awaiting(n: Int, p: Promise[Unit, Int]) = State(0L, Some((n, p)))

    private val state = new AtomicReference(initial)

    def offer(n: Int): IO[Unit, Int] = {
      var result: IO[Unit, Int] = null
      state.updateAndGet {
        case `canceled` =>
          result = IO.fail(())
          canceled
        case State(0L, _) =>
          val p = Promise.unsafeMake[Unit, Int](FiberId.None)
          result = p.await
          awaiting(n, p)
        case State(requestedCount, _) =>
          val newRequestedCount = Math.max(requestedCount - n, 0L)
          val accepted          = Math.min(requestedCount, n.toLong).toInt
          result = IO.succeedNow(accepted)
          requested(newRequestedCount)
      }
      result
    }

    def isCanceled: Boolean = state.get().requestedCount < 0

    override def request(n: Long): Unit = {
      if (n <= 0) subscriber.onError(new IllegalArgumentException("non-positive subscription request"))
      var notification: () => Unit = () => ()
      state.getAndUpdate {
        case `canceled` =>
          canceled
        case State(requestedCount, Some((offered, toNotify))) =>
          val newRequestedCount = requestedCount + n
          val accepted          = Math.min(offered.toLong, newRequestedCount)
          val remaining         = newRequestedCount - accepted
          notification = () => toNotify.unsafeDone(IO.succeedNow(accepted.toInt))
          requested(remaining)
        case State(requestedCount, _) if ((Long.MaxValue - n) > requestedCount) =>
          requested(requestedCount + n)
        case _ =>
          requested(Long.MaxValue)
      }
      notification()
    }

    override def cancel(): Unit =
      state.getAndSet(canceled).toNotify.foreach { case (_, p) => p.unsafeDone(IO.fail(())) }
  }
}
