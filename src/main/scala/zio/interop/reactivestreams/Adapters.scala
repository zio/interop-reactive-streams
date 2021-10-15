package zio.interop.reactivestreams

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import zio.Exit.Failure
import zio.Exit.Success
import zio._
import zio.stream.ZSink
import zio.stream.ZStream
import zio.stream.ZStream.Pull

object Adapters {

  def streamToPublisher[R, E <: Throwable, O](stream: ZStream[R, E, O]): ZIO[R, Nothing, Publisher[O]] =
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
    subscriber: Subscriber[I]
  ): ZManaged[Any, Nothing, (Promise[E, Nothing], ZSink[Any, Nothing, I, I, Unit])] =
    for {
      runtime     <- ZIO.runtime[Any].toManaged
      demand      <- Queue.unbounded[Long].toManaged
      error       <- Promise.make[E, Nothing].toManaged
      subscription = createSubscription(subscriber, demand, runtime)
      _           <- UIO(subscriber.onSubscribe(subscription)).toManaged
      _           <- error.await.catchAll(t => UIO(subscriber.onError(t)) *> demand.shutdown).toManaged.fork
    } yield (error, demandUnfoldSink(subscriber, demand))

  def publisherToStream[O](publisher: Publisher[O], bufferSize: Int): ZStream[Any, Throwable, O] = {
    val pullOrFail =
      for {
        subscriberP    <- makeSubscriber[O](bufferSize)
        (subscriber, p) = subscriberP
        _              <- ZManaged.succeed(publisher.subscribe(subscriber))
        subQ           <- p.await.toManaged
        (sub, q)        = subQ
        process        <- process(q, sub)
      } yield process
    val pull = pullOrFail.catchAll(e => ZManaged.succeed(Pull.fail(e)))
    ZStream(pull)
  }

  def sinkToSubscriber[R, I, L, Z](
    sink: ZSink[R, Throwable, I, L, Z],
    bufferSize: Int
  ): ZManaged[R, Throwable, (Subscriber[I], IO[Throwable, Z])] =
    for {
      subscriberP    <- makeSubscriber[I](bufferSize)
      (subscriber, p) = subscriberP
      pull = p.await.toManaged.flatMap { case (subscription, q) => process(q, subscription) }
               .catchAll(e => ZManaged.succeedNow(Pull.fail(e)))
      fiber <- ZStream(pull).run(sink).toManaged.fork
    } yield (subscriber, fiber.join)

  private def process[R, A](
    q: Queue[Exit[Option[Throwable], A]],
    sub: Subscription
  ): ZManaged[Any, Nothing, ZIO[Any, Option[Throwable], Chunk[A]]] = {
    val capacity = q.capacity.toLong - 1 // leave space for End or Fail
    for {
      _         <- ZManaged.succeed(sub.request(capacity))
      requested <- Ref.Synchronized.makeManaged(capacity)
      lastP     <- Promise.makeManaged[Option[Throwable], Chunk[A]]
    } yield {

      def takesToPull(
        takes: Chunk[Exit[Option[Throwable], A]]
      ): Pull[Any, Throwable, A] = {
        val toEmit = takes.collectWhile { case Exit.Success(a) => a }
        val pull   = Pull.emit(toEmit)
        if (toEmit.size == takes.size) {
          val chunkSize = toEmit.size
          val request =
            requested.getAndUpdateZIO {
              case `chunkSize` => UIO(sub.request(capacity)).as(capacity)
              case n           => UIO.succeedNow(n - chunkSize)
            }
          request *> pull
        } else {
          val failure = takes.drop(toEmit.size).head
          failure match {
            case Failure(cause) =>
              val last =
                Cause.flipCauseOption(cause) match {
                  case None        => Pull.end
                  case Some(cause) => Pull.failCause(cause)
                }
              if (toEmit.isEmpty) last else lastP.complete(last) *> pull

            case Success(_) => pull // should never happen
          }
        }
      }

      lastP.isDone.flatMap {
        case true  => lastP.await
        case false => q.takeBetween(1, q.capacity).flatMap(takesToPull)
      }

    }
  }

  private def makeSubscriber[A](
    capacity: Int
  ): UManaged[(Subscriber[A], Promise[Throwable, (Subscription, Queue[Exit[Option[Throwable], A]])])] =
    for {
      q <- Queue
             .bounded[Exit[Option[Throwable], A]](capacity)
             .toManagedWith(_.shutdown)
      p <- Promise
             .make[Throwable, (Subscription, Queue[Exit[Option[Throwable], A]])]
             .toManagedWith(
               _.poll.flatMap(_.fold(UIO.unit)(_.foldZIO(_ => UIO.unit, { case (sub, _) => UIO(sub.cancel()) })))
             )
      runtime <- ZManaged.runtime[Any]
    } yield {

      val subscriber =
        new Subscriber[A] {

          override def onSubscribe(s: Subscription): Unit =
            if (s == null) {
              val e = new NullPointerException("s was null in onSubscribe")
              runtime.unsafeRun(p.fail(e))
              throw e
            } else {
              runtime.unsafeRun(
                p.succeed((s, q)).flatMap {
                  // `whenM(q.isShutdown)`, the Stream has been interrupted or completed before we received `onSubscribe`
                  case true  => UIO(s.cancel()).whenZIO(q.isShutdown).unit
                  case false => UIO(s.cancel())
                }
              )
            }

          override def onNext(t: A): Unit =
            if (t == null) {
              val e = new NullPointerException("t was null in onNext")
              runtime.unsafeRun(q.offer(Exit.fail(Some(e))))
              throw e
            } else {
              runtime.unsafeRunSync(q.offer(Exit.succeed(t)))
              ()
            }

          override def onError(e: Throwable): Unit =
            if (e == null) {
              val e = new NullPointerException("t was null in onError")
              runtime.unsafeRun(q.offer(Exit.fail(Some(e))))
              throw e
            } else {
              runtime.unsafeRun(q.offer(Exit.fail(Some(e))).unit)
            }

          override def onComplete(): Unit =
            runtime.unsafeRun(q.offer(Exit.fail(None)).unit)
        }
      (subscriber, p)
    }

  def demandUnfoldSink[I](
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
                    .foreach(chunk)(a => UIO(subscriber.onNext(a)))
                    .as((Chunk.empty, bufferedDemand - chunk.size.toLong))
                else
                  UIO.foreach(chunk.take(bufferedDemand.toInt))(a => UIO(subscriber.onNext(a))) *>
                    demand.take.map((chunk.drop(bufferedDemand.toInt), _))
            }
          }
          .map(_._2)
      }
      .mapZIO(_ => demand.isShutdown.flatMap(is => UIO(subscriber.onComplete()).when(!is).unit))

  def createSubscription[A](
    subscriber: Subscriber[_ >: A],
    demand: Queue[Long],
    runtime: Runtime[_]
  ): Subscription =
    new Subscription {
      override def request(n: Long): Unit = {
        if (n <= 0) subscriber.onError(new IllegalArgumentException("non-positive subscription request"))
        runtime.unsafeRunAsync(demand.offer(n))
      }
      override def cancel(): Unit = runtime.unsafeRun(demand.shutdown)
    }
}
