package zio.interop.reactivestreams

import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import zio._
import zio.stream.{ ZSink, ZStream }
import zio.stream.ZStream.{ Pull, Take }

object Adapters {

  def streamToPublisher[R, E <: Throwable, A](stream: ZStream[R, E, A]): ZIO[R, Nothing, Publisher[A]] =
    ZIO.runtime.map { runtime => (subscriber: Subscriber[_ >: A]) =>
      if (subscriber == null) {
        throw new NullPointerException("Subscriber must not be null.")
      } else {
        runtime.unsafeRunAsync_(
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

  def subscriberToSink[E <: Throwable, A](
    subscriber: Subscriber[A]
  ): UIO[(Promise[E, Nothing], ZSink[Any, Nothing, A, Unit])] =
    for {
      runtime      <- ZIO.runtime[Any]
      demand       <- Queue.unbounded[Long]
      error        <- Promise.make[E, Nothing]
      subscription = createSubscription(subscriber, demand, runtime)
      _            <- UIO(subscriber.onSubscribe(subscription))
      _            <- error.await.catchAll(t => UIO(subscriber.onError(t)) *> demand.shutdown).forkDaemon
    } yield (error, demandUnfoldSink(subscriber, demand))

  def publisherToStream[A](publisher: Publisher[A], bufferSize: Int): ZStream[Any, Throwable, A] = {
    val pullOrFail =
      for {
        (subscriber, p) <- makeSubscriber[A](bufferSize)
        _               <- UIO(publisher.subscribe(subscriber)).toManaged_
        (sub, q)        <- p.await.toManaged_
        process         <- process(q, sub)
      } yield process
    val pull = pullOrFail.catchAll(e => UIO(Pull.fail(e)).toManaged_)
    ZStream(pull)
  }

  def sinkToSubscriber[R, R1 <: R, A, B](
    sink: ZSink[R, Throwable, A, B],
    bufferSize: Int
  ): ZManaged[R1, Throwable, (Subscriber[A], IO[Throwable, B])] =
    for {
      (subscriber, p) <- makeSubscriber[A](bufferSize)
      pull = p.await.toManaged_.flatMap { case (subscription, q) => process(q, subscription) }
        .catchAll(e => ZManaged.succeedNow(Pull.fail(e)))
      fiber <- ZStream(pull).run(sink).toManaged_.fork
    } yield (subscriber, fiber.join)

  private def process[R, A](
    q: Queue[Take[Throwable, A]],
    sub: Subscription
  ): ZManaged[Any, Nothing, ZIO[Any, Option[Throwable], Chunk[A]]] = {
    val capacity = q.capacity.toLong - 1 // leave space for End or Fail
    for {
      _         <- UIO(sub.request(capacity)).toManaged_
      requested <- RefM.make(capacity).toManaged_
      lastP     <- Promise.make[Option[Throwable], Chunk[A]].toManaged_
    } yield lastP.isDone.flatMap {
      case true => lastP.await
      case false =>
        q.takeBetween(1, q.capacity).flatMap { all =>
          val (values, rest) =
            all.span {
              case Exit.Success(_) => true
              case _               => false
            }
          if (values.isEmpty) {
            rest.headOption match { // avoid emitting an empty Chunk
              case Some(Exit.Failure(cause)) =>
                Cause.sequenceCauseOption(cause) match {
                  case Some(cause) => Pull.halt(cause)
                  case None        => Pull.end
                }
              case _ =>
                IO.die(new IllegalStateException("Impossible state in process."))
            }
          } else {
            val completeOrRequest =
              rest.headOption match {
                case Some(Exit.Failure(cause)) =>
                  val last =
                    Cause.sequenceCauseOption(cause) match {
                      case Some(cause) => Pull.halt(cause)
                      case None        => Pull.end
                    }
                  lastP.complete(last)
                case _ =>
                  val request = values.length
                  requested.getAndUpdate {
                    case `request` => UIO(sub.request(capacity)).as(capacity)
                    case n         => UIO.succeedNow(n - request)
                  }
              }
            val emit =
              Pull.emit(
                Chunk
                  .fromIterable(
                    values.collect {
                      case Exit.Success(v) => v
                    }
                  )
                  .flatten
              )
            completeOrRequest *> emit
          }
        }
    }
  }

  private def makeSubscriber[A](
    capacity: Int
  ): UManaged[(Subscriber[A], Promise[Throwable, (Subscription, Queue[Take[Throwable, A]])])] =
    for {
      q <- Queue
            .bounded[Take[Throwable, A]](capacity)
            .toManaged(_.shutdown)
      p <- Promise
            .make[Throwable, (Subscription, Queue[Take[Throwable, A]])]
            .toManaged(p => ZIO.whenM(p.isDone)(p.await.fold(_ => UIO.unit, { case (sub, _) => UIO(sub.cancel()) })))
      runtime <- ZIO.runtime[Any].toManaged_
    } yield {

      val subscriber =
        new Subscriber[A] {

          override def onSubscribe(s: Subscription): Unit =
            if (s == null) {
              val e = new NullPointerException("s was null in onSubscribe")
              runtime.unsafeRun(p.fail(e))
              throw e
            } else {
              runtime.unsafeRun(p.succeed((s, q)).flatMap {
                // `whenM(q.isShutdown)`, the Stream has been interrupted or completed before we received `onSubscribe`
                case true  => UIO(s.cancel()).whenM(q.isShutdown)
                case false => UIO(s.cancel())
              })
            }

          override def onNext(t: A): Unit =
            if (t == null) {
              val e = new NullPointerException("t was null in onNext")
              runtime.unsafeRun(q.offer(Exit.fail(Some(e))))
              throw e
            } else {
              runtime.unsafeRunSync(q.offer(Exit.succeed(Chunk.single(t))))
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
            runtime.unsafeRun(q.offer(Take.End).unit)
        }
      (subscriber, p)
    }

  def demandUnfoldSink[A](
    subscriber: Subscriber[_ >: A],
    demand: Queue[Long]
  ): ZSink[Any, Nothing, A, Unit] =
    ZSink
      .foldM[Any, Nothing, A, (Long, Boolean)]((0L, true))(_._2) {
        case (state, a) =>
          demand.isShutdown.flatMap {
            case true                  => UIO((state._1, false))
            case false if state._1 > 0 => UIO(subscriber.onNext(a)).as((state._1 - 1, true))
            case false                 => demand.take.flatMap(n => UIO(subscriber.onNext(a)).as((n - 1, true)))
          }
      }
      .mapM(_ => demand.isShutdown.flatMap(is => UIO(subscriber.onComplete()).when(!is)))

  def createSubscription[A](
    subscriber: Subscriber[_ >: A],
    demand: Queue[Long],
    runtime: Runtime[_]
  ): Subscription =
    new Subscription {
      override def request(n: Long): Unit = {
        if (n <= 0) subscriber.onError(new IllegalArgumentException("non-positive subscription request"))
        runtime.unsafeRunAsync_(demand.offer(n).unit)
      }
      override def cancel(): Unit = runtime.unsafeRun(demand.shutdown)
    }
}
