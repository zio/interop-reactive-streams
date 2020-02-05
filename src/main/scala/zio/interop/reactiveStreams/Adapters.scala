package zio.interop.reactiveStreams

import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import zio._
import zio.stream.{ ZSink, ZStream }
import zio.stream.ZStream.Pull

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
                  .fork
          } yield ()
        )
      }
    }

  def subscriberToSink[E <: Throwable, A](
    subscriber: Subscriber[A]
  ): UIO[(Promise[E, Nothing], ZSink[Any, Nothing, Unit, A, Unit])] =
    for {
      runtime      <- ZIO.runtime[Any]
      demand       <- Queue.unbounded[Long]
      error        <- Promise.make[E, Nothing]
      subscription = createSubscription(subscriber, demand, runtime)
      _            <- UIO(subscriber.onSubscribe(subscription))
      _            <- error.await.catchAll(t => UIO(subscriber.onError(t)) *> demand.shutdown).fork
    } yield (error, demandUnfoldSink(subscriber, demand))

  def publisherToStream[A](publisher: Publisher[A], bufferSize: Int): ZStream[Any, Throwable, A] =
    ZStream(
      for {
        (q, subscription, completion, subscriber) <- makeSubscriber[A](bufferSize).toManaged_
        _                                         <- UIO(publisher.subscribe(subscriber)).toManaged_
        sub <- ZManaged
                .fromEffect(subscription.await)
                .onExitFirst(_.foreach(sub => UIO { sub.cancel() }.whenM(completion.isDone.map(!_))))
        process <- process(q, sub, completion)
      } yield process
    )

  def sinkToSubscriber[R, R1 <: R, A1, A, B](
    sink: ZSink[R, Throwable, A1, A, B],
    bufferSize: Int
  ): ZManaged[R1, Throwable, (Subscriber[A], IO[Throwable, B])] =
    for {
      (q, subscription, completion, subscriber) <- makeSubscriber[A](bufferSize).toManaged_
      result                                    <- Promise.make[Throwable, B].toManaged_
      _ <- ZStream(for {
            sub <- ZManaged
                    .fromEffect(subscription.await)
                    .onExitFirst(_.foreach(sub => UIO { sub.cancel() }.whenM(completion.isDone.map(!_))))
            process <- process(q, sub, completion)
          } yield process)
            .run(sink)
            .to(result)
            .interruptible // `to` is not interruptible by default
            .fork
            .toManaged(_.interrupt)
    } yield (subscriber, result.await)

  /*
   * Unfold q. When `onComplete` or `onError` is signalled, take the remaining values from `q`, then shut down.
   * `onComplete` or `onError` always are the last signal if they occur. We optimistically take from `q` and rely on
   * interruption in case that they are signalled while we wait. `forkQShutdownHook` ensures that `take` is
   * interrupted in case `q` is empty while `onComplete` or `onError` is signalled.
   * When we see `completion.done`, the `Publisher` has signalled `onComplete` or `onError` and we are
   * done. Otherwise the stream has completed before and we need to cancel the subscription.
   */
  private def process[R, A](
    q: Queue[A],
    sub: Subscription,
    completion: Promise[Throwable, Unit]
  ): ZManaged[R, Throwable, Pull[Any, Throwable, A]] =
    for {
      _ <- ZManaged.finalizer(q.shutdown)
      _ <- completion.await.run
            .ensuring(q.size.flatMap(n => if (n <= 0) q.shutdown else UIO.unit))
            .toManaged_
            .fork
      demand <- Ref.make(0L).toManaged_
    } yield {

      val capacity: Long = q.capacity.toLong

      val requestAndTake = demand.modify(d => (sub.request(capacity - d), capacity - 1)) *> q.take.flatMap(Pull.emit)
      val take           = demand.update(_ - 1) *> q.take.flatMap(Pull.emit)

      q.size.flatMap {
        case n if n <= 0 =>
          completion.isDone.flatMap {
            case true  => completion.await.foldM(Pull.fail, _ => Pull.end)
            case false => demand.get.flatMap(d => if (d < capacity) requestAndTake else take)
          }
        case _ => take
      }.orElse(
        completion.poll.flatMap {
          case None     => Pull.end
          case Some(io) => io.foldM(Pull.fail, _ => Pull.end)
        }
      )
    }

  private def makeSubscriber[A](
    capacity: Int
  ): UIO[(Queue[A], Promise[Throwable, Subscription], Promise[Throwable, Unit], Subscriber[A])] =
    for {
      runtime      <- ZIO.runtime[Any]
      q            <- Queue.bounded[A](capacity)
      subscription <- Promise.make[Throwable, Subscription]
      completion   <- Promise.make[Throwable, Unit]
    } yield {
      val subscriber = new Subscriber[A] {

        override def onSubscribe(s: Subscription): Unit =
          if (s == null) {
            val e = new NullPointerException("s was null in onSubscribe")
            runtime.unsafeRun(subscription.fail(e))
            throw e
          } else {
            runtime.unsafeRun(subscription.succeed(s).flatMap {
              // `whenM(q.isShutdown)`, the Stream has been interrupted or completed before we received `onSubscribe`
              case true  => UIO(s.cancel()).whenM(q.isShutdown)
              case false => UIO(s.cancel())
            })
          }

        override def onNext(t: A): Unit =
          if (t == null) {
            val e = new NullPointerException("t was null in onNext")
            runtime.unsafeRun(completion.fail(e))
            throw e
          } else {
            runtime.unsafeRunSync(q.offer(t))
            ()
          }

        override def onError(e: Throwable): Unit =
          if (e == null) {
            val e = new NullPointerException("t was null in onError")
            runtime.unsafeRun(completion.fail(e))
            throw e
          } else {
            runtime.unsafeRun(completion.fail(e).unit)
          }

        override def onComplete(): Unit = runtime.unsafeRun(completion.succeed(()).unit)
      }
      (q, subscription, completion, subscriber)
    }

  def demandUnfoldSink[A](
    subscriber: Subscriber[_ >: A],
    demand: Queue[Long]
  ): ZSink[Any, Nothing, Nothing, A, Unit] =
    new ZSink[Any, Nothing, Nothing, A, Unit] {

      override type State = (Long, Boolean)

      def cont(state: State): Boolean = state._2

      override def extract(state: State): UIO[(Unit, Chunk[Nothing])] =
        demand.isShutdown.flatMap(is => UIO(subscriber.onComplete()).when(!is)) *> UIO.succeed(((), Chunk.empty))

      override def initial: UIO[State] = UIO((0L, true))

      override def step(state: State, a: A): UIO[State] =
        demand.isShutdown.flatMap {
          case true                  => UIO((state._1, false))
          case false if state._1 > 0 => UIO(subscriber.onNext(a)).map(_ => (state._1 - 1, true))
          case false                 => demand.take.flatMap(n => UIO(subscriber.onNext(a)).map(_ => (n - 1, true)))
        }

    }

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
