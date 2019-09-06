package zio.interop.reactiveStreams

import org.reactivestreams.{ Subscriber, Subscription }
import zio.stream.ZStream
import zio.{ Promise, Queue, UIO, ZIO }
import zio.ZManaged
import zio.stream.ZStream.Pull
import zio.Ref
import org.reactivestreams.Publisher
import zio.UIO
import zio.stream.ZSink

private[reactiveStreams] object QueueSubscriber {

  def publisherToStream[A](publisher: Publisher[A], bufferSize: Int): ZStream[Any, Throwable, A] =
    new ZStream(
      for {
        (q, subscription, completion, subscriber) <- makeSubscriber[A](bufferSize).toManaged_
        _                                         <- UIO(publisher.subscribe(subscriber)).toManaged_
        sub <- subscription.await.interruptible
                .toManaged(sub => UIO { sub.cancel() }.whenM(completion.isDone.map(!_)))
        process <- process(q, sub, completion)
      } yield process
    )

  def sinkToSubscriber[R, R1 <: R, A1, A, B](
    sink: ZSink[R, Throwable, A1, A, B],
    bufferSize: Int
  )(subscribe: Subscriber[A] => ZIO[R1, Throwable, Unit]): ZIO[R1, Throwable, B] =
    new ZStream[R1, Throwable, A](for {
      (q, subscription, completion, subscriber) <- makeSubscriber[A](bufferSize).toManaged_
      _                                         <- subscribe(subscriber).fork.toManaged_
      sub                                       <- subscription.await.interruptible.toManaged(sub => UIO { sub.cancel() }.whenM(completion.isDone.map(!_)))
      process                                   <- process(q, sub, completion)
    } yield process).run(sink)

  /*
   * Unfold q. When `onComplete` or `onError` is signalled, take the remaining values from `q`, then shut down.
   * `onComplete` or `onError` always are the last signal if they occur. We optimistically take from `q` and rely on
   * interruption in case that they are signalled while we wait. `forkQShutdownHook` ensures that `take` is
   * interrupted in case `q` is empty while `onComplete` or `onError` is signalled.
   * When we see `completion.done` after `loop`, the `Publisher` has signalled `onComplete` or `onError` and we are
   * done. Otherwise the stream has completed before and we need to cancel the subscription.
   */
  private def process[R, A](
    q: Queue[A],
    sub: Subscription,
    completion: Promise[Throwable, Unit]
  ): ZManaged[R, Throwable, Pull[Any, Throwable, A]] =
    for {
      _      <- ZManaged.finalizer(q.shutdown)
      _      <- completion.await.ensuring(q.size.flatMap(n => if (n <= 0) q.shutdown else UIO.unit)).fork.toManaged_
      demand <- Ref.make(0L).toManaged_
    } yield {

      val capacity: Long = q.capacity.toLong

      val requestAndTake = demand.modify(d => (sub.request(capacity - d), capacity - 1)) *> q.take.flatMap(Pull.emit)
      val take           = demand.update(_ - 1) *> q.take.flatMap(Pull.emit)

      q.size.flatMap { n =>
        if (n <= 0) completion.isDone.flatMap {
          case true  => completion.await.foldM(Pull.fail, _ => Pull.end)
          case false => demand.get.flatMap(d => if (d < capacity) requestAndTake else take)
        } else take
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
}
