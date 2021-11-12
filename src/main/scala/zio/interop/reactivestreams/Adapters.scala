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
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

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
      .foldChunksZIO[Any, Nothing, I, Long](0L)(_ >= 0L) { (bufferedDemand, chunk) =>
        UIO
          .iterate((chunk, bufferedDemand))(!_._1.isEmpty) { case (chunk, bufferedDemand) =>
            if (subscription.canceled) UIO.succeedNow((Chunk.empty, -1))
            else if (chunk.size.toLong <= bufferedDemand)
              UIO
                .foreach(chunk)(a => UIO(subscriber.onNext(a)))
                .as((Chunk.empty, bufferedDemand - chunk.size.toLong))
            else
              UIO.foreach(chunk.take(bufferedDemand.toInt))(a => UIO(subscriber.onNext(a))) *>
                subscription.requested.fold(_ => (Chunk.empty, -1L), (chunk.drop(bufferedDemand.toInt), _))
          }
          .map(_._2)
      }
      .map(_ => if (!subscription.canceled) subscriber.onComplete())

  private class DemandTrackingSubscription(subscriber: Subscriber[_]) extends Subscription {

    // < 0 when cancelled
    private val requestedCount = new AtomicLong(0L)
    @volatile
    private var toNotify: Option[Promise[Unit, Long]] = None

    def requested: IO[Unit, Long] = {
      val got = requestedCount.getAndUpdate(old => if (old >= 0L) 0L else old)
      if (got < 0L) {
        IO.fail(())
      } else if (got > 0L) {
        IO.succeedNow(got)
      } else {
        val p = Promise.unsafeMake[Unit, Long](FiberId.None)
        toNotify = Some(p)
        val got = requestedCount.getAndUpdate(old => if (old >= 0L) 0L else old)
        if (got != 0L) {
          toNotify = None
          if (got < 0L)
            IO.fail(())
          else
            IO.succeedNow(got)
        } else {
          p.await
        }
      }
    }

    def canceled: Boolean = requestedCount.get() < 0L

    override def request(n: Long): Unit = {
      if (n <= 0) subscriber.onError(new IllegalArgumentException("non-positive subscription request"))
      requestedCount.getAndUpdate { old =>
        if (old >= 0L)
          toNotify match {
            case Some(p) =>
              p.unsafeDone(IO.succeedNow(n))
              toNotify = None
              old
            case None =>
              try {
                Math.addExact(old, n)
              } catch { case NonFatal(_) => Long.MaxValue }
          }
        else
          old
      }
      ()
    }

    override def cancel(): Unit = {
      requestedCount.set(-1L)
      toNotify.foreach(_.unsafeDone(IO.fail(())))
    }
  }
}
