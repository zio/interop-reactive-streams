package zio.interop.reactivestreams

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import zio._
import zio.stream.ZSink
import zio.stream.ZStream
import zio.stream.ZStream.Pull

import scala.annotation.tailrec

object Adapters {

  def streamToPublisher[R, E <: Throwable, O](stream: ZStream[R, E, O]): ZIO[R, Nothing, Publisher[O]] =
    ZIO.runtime.map { runtime => subscriber =>
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

  def subscriberToSink[E <: Throwable, I](
    subscriber: Subscriber[I]
  ): UIO[(Promise[E, Nothing], ZSink[Any, Nothing, I, I, Unit])] =
    for {
      runtime     <- ZIO.runtime[Any]
      demand      <- Queue.unbounded[Long]
      error       <- Promise.make[E, Nothing]
      subscription = createSubscription(subscriber, demand, runtime)
      _           <- UIO(subscriber.onSubscribe(subscription))
      _           <- error.await.catchAll(t => UIO(subscriber.onError(t)) *> demand.shutdown).forkDaemon
    } yield (error, demandUnfoldSink(subscriber, demand))

  def publisherToStream[O](publisher: Publisher[O], bufferSize: Int): ZStream[Any, Throwable, O] = {
    val pullOrFail =
      for {
        subscriberP    <- makeSubscriber[O](bufferSize)
        (subscriber, p) = subscriberP
        _              <- UIO(publisher.subscribe(subscriber)).toManaged_
        subQ           <- p.await.toManaged_
        (sub, q)        = subQ
        process        <- process(q, sub)
      } yield process
    val pull = pullOrFail.catchAll(e => UIO(Pull.fail(e)).toManaged_)
    ZStream(pull)
  }

  def sinkToSubscriber[R, I, L, Z](
    sink: ZSink[R, Throwable, I, L, Z],
    bufferSize: Int
  ): ZManaged[R, Throwable, (Subscriber[I], IO[Throwable, Z])] =
    for {
      subscriberP    <- makeSubscriber[I](bufferSize)
      (subscriber, p) = subscriberP
      pull = p.await.toManaged_.flatMap { case (subscription, q) => process(q, subscription) }
               .catchAll(e => ZManaged.succeedNow(Pull.fail(e)))
      fiber <- ZStream(pull).run(sink).toManaged_.fork
    } yield (subscriber, fiber.join)

  private def process[R, A](
    q: Queue[Exit[Option[Throwable], A]],
    sub: Subscription
  ): ZManaged[Any, Nothing, ZIO[Any, Option[Throwable], Chunk[A]]] = {
    val capacity = q.capacity.toLong - 1 // leave space for End or Fail
    for {
      _         <- UIO(sub.request(capacity)).toManaged_
      requested <- RefM.make(capacity).toManaged_
      lastP     <- Promise.make[Option[Throwable], Chunk[A]].toManaged_
    } yield {

      @tailrec
      def takesToPull(
        builder: ChunkBuilder[A] = ChunkBuilder.make[A]()
      )(
        takes: List[Exit[Option[Throwable], A]]
      ): Pull[Any, Throwable, A] =
        takes match {
          case Exit.Success(a) :: tail =>
            builder += a
            takesToPull(builder)(tail)
          case Exit.Failure(cause) :: _ =>
            val chunk = builder.result()
            val pull = Cause.sequenceCauseOption(cause) match {
              case Some(cause) => Pull.halt(cause)
              case None        => Pull.end
            }
            if (chunk.isEmpty) pull else lastP.complete(pull) *> Pull.emit(chunk)
          case Nil =>
            val chunk = builder.result()
            val pull  = Pull.emit(chunk)

            if (chunk.isEmpty) pull
            else {
              val chunkSize = chunk.size
              val request =
                requested.getAndUpdate {
                  case `chunkSize` => UIO(sub.request(capacity)).as(capacity)
                  case n           => UIO.succeedNow(n - chunkSize)
                }
              request *> pull
            }
        }

      lastP.isDone.flatMap {
        case true  => lastP.await
        case false => q.takeBetween(1, q.capacity).flatMap(takesToPull())
      }

    }
  }

  private def makeSubscriber[A](
    capacity: Int
  ): UManaged[(Subscriber[A], Promise[Throwable, (Subscription, Queue[Exit[Option[Throwable], A]])])] =
    for {
      q <- Queue
             .bounded[Exit[Option[Throwable], A]](capacity)
             .toManaged(_.shutdown)
      p <- Promise
             .make[Throwable, (Subscription, Queue[Exit[Option[Throwable], A]])]
             .toManaged(p =>
               p.poll.flatMap(_.fold(UIO.unit)(_.foldM(_ => UIO.unit, { case (sub, _) => UIO(sub.cancel()) })))
             )
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
              runtime.unsafeRunSync(q.offer(Exit.fail(Some(e))))
              throw e
            } else {
              runtime.unsafeRunSync(q.offer(Exit.succeed(t)))
              ()
            }

          override def onError(e: Throwable): Unit =
            if (e == null) {
              val e = new NullPointerException("t was null in onError")
              runtime.unsafeRunSync(q.offer(Exit.fail(Some(e))))
              throw e
            } else {
              runtime.unsafeRunSync(q.offer(Exit.fail(Some(e))))
              ()
            }

          override def onComplete(): Unit = {
            runtime.unsafeRunSync(q.offer(Exit.fail(None)))
            ()
          }
        }
      (subscriber, p)
    }

  def demandUnfoldSink[I](
    subscriber: Subscriber[_ >: I],
    demand: Queue[Long]
  ): ZSink[Any, Nothing, I, I, Unit] =
    ZSink
      .foldChunksM[Any, Nothing, I, Long](0L)(_ >= 0L) { (bufferedDemand, chunk) =>
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
