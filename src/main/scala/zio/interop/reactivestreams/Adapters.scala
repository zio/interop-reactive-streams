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

object Adapters {

  def streamToPublisher[R, E <: Throwable, O](
    stream: => ZStream[R, E, O]
  )(implicit trace: ZTraceElement): ZIO[R, Nothing, Publisher[O]] =
    ZIO.runtime.map { runtime => subscriber =>
      if (subscriber == null) {
        throw new NullPointerException("Subscriber must not be null.")
      } else {
        runtime.unsafeRunAsync(
          for {
            demand <- Queue.unbounded[Long]
            _      <- UIO(subscriber.onSubscribe(createSubscription(subscriber, demand, runtime)))
            _ <- stream
                   .run(demandUnfoldSink(subscriber, demand))
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
      runtime     <- ZIO.runtime[Any].toManaged
      demand      <- Queue.unbounded[Long].toManaged
      error       <- Promise.make[E, Nothing].toManaged
      subscription = createSubscription(sub, demand, runtime)
      _           <- ZManaged.succeed(sub.onSubscribe(subscription))
      _           <- error.await.catchAll(t => UIO(sub.onError(t)) *> demand.shutdown).toManaged.fork
    } yield (error, demandUnfoldSink(sub, demand))
  }

  def publisherToStream[O](
    publisher: => Publisher[O],
    bufferSize: => Int
  )(implicit trace: ZTraceElement): ZStream[Any, Throwable, O] = {

    val pullOrFail =
      for {
        subscriberP    <- makeSubscriber[O](bufferSize)
        (subscriber, p) = subscriberP
        _              <- ZManaged.acquireReleaseSucceed(publisher.subscribe(subscriber))(subscriber.interrupt())
        subQ <- ZManaged.fromZIOUninterruptible(
                  p.await.interruptible
                    .onTermination(_ => UIO(subscriber.interrupt()))
                )
        (sub, q) = subQ
        process <- process(sub, q, () => subscriber.await(), () => subscriber.isDone)
      } yield process
    val pull = pullOrFail.catchAll(e => ZManaged.succeed(Pull.fail(e)))
    ZStream.fromPull(pull)
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
      fiber <- ZStream.fromPull(pull).run(sink).toManaged.fork
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

          override def interrupt(): Unit =
            // println("IS interrupt")
            isSubscribedOrInterrupted.set(true)

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
    subscriber: Subscriber[_ >: I],
    demand: Queue[Long]
  ): ZSink[Any, Nothing, I, I, Unit] =
    ZSink
      .foldChunksZIO[Any, Nothing, I, Long](0L)(_ >= 0L) { (bufferedDemand, chunk) =>
        UIO
          .iterate((chunk, bufferedDemand))(!_._1.isEmpty) { case (chunk, bufferedDemand) =>
            demand.isShutdown.flatMap {
              case true => UIO((Chunk.empty, -1))
              case false =>
                if (chunk.size.toLong <= bufferedDemand)
                  UIO
                    .foreachDiscard(chunk)(a => UIO(subscriber.onNext(a)))
                    .as((Chunk.empty, bufferedDemand - chunk.size.toLong))
                else
                  UIO.foreachDiscard(chunk.take(bufferedDemand.toInt))(a => UIO(subscriber.onNext(a))) *>
                    demand.take.map((chunk.drop(bufferedDemand.toInt), _))
            }
          }
          .map(_._2)
      }
      .mapZIO(_ => demand.isShutdown.flatMap(is => UIO(subscriber.onComplete()).when(!is).unit))

  private def createSubscription[A](
    subscriber: Subscriber[_ >: A],
    demand: Queue[Long],
    runtime: Runtime[_]
  ): Subscription =
    new Subscription {
      override def request(n: Long): Unit =
        if (n <= 0) subscriber.onError(new IllegalArgumentException("non-positive subscription request"))
        else runtime.unsafeRunAsync(demand.offer(n))
      override def cancel(): Unit = runtime.unsafeRun(demand.shutdown)
    }
}
