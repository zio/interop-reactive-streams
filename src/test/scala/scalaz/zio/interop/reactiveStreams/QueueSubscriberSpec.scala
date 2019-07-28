package zio.interop.reactiveStreams

import org.reactivestreams.Subscription
import org.specs2.execute.Result
import org.specs2.specification.core.SpecStructure
import scala.concurrent.duration._
import zio._
import zio.stream.Sink
import zio.duration.Duration

class QueueSubscriberSpec extends BaseCrossPlatformSpec {

  def is: SpecStructure =
    "QueueSubscriberSpec".title ^ s2"""
   A QueueSubscriber should
     not throw Exceptions after the queue has been shut down $e1
     cancel the subscription if the Stream fails before the subscription happens $e2
     cancel the subscription if the Stream fails after subscription arrives $e3
     cancel the subscription if the Stream fails after consuming some values $e4
     cancel the subscription if the Stream is interrupted before the subscription happens $e5
     cancel the subscription if the Stream is interrupted after subscription arrives $e6
    """

  private val boom = new Throwable("boom")

  private def e1 = unsafeRun(
    for {
      subStr          <- QueueSubscriber.make[Int](10)
      (subscriber, _) = subStr
      _               <- UIO(subscriber.onComplete())
      _               <- UIO(subscriber.onNext(1))
      _               <- UIO(subscriber.onError(boom))
    } yield success
  )

  private def e2 =
    unsafeRun(
      for {
        subStr               <- QueueSubscriber.make[Int](10)
        (subscriber, stream) = subStr
        fiber                <- stream.run(Sink.fail(boom)).fork
        canceled             <- Promise.make[Nothing, Result]
        runtime              <- ZIO.runtime[Any]
        s = new Subscription {
          override def request(n: Long): Unit = runtime.unsafeRun(canceled.succeed(failure).unit)
          override def cancel(): Unit         = runtime.unsafeRun(canceled.succeed(success).unit)
        }
        _  <- UIO(subscriber.onSubscribe(s))
        _  <- fiber.await
        ro <- canceled.poll
        r  <- ro.fold(UIO.succeed[Result](failure))(identity)
      } yield r
    )

  private def e3 =
    unsafeRun(
      for {
        subStr               <- QueueSubscriber.make[Int](10)
        (subscriber, stream) = subStr
        canceled             <- Promise.make[Nothing, Result]
        runtime              <- ZIO.runtime[Any]
        s = new Subscription {
          override def request(n: Long): Unit = runtime.unsafeRun(canceled.succeed(failure).unit)
          override def cancel(): Unit         = runtime.unsafeRun(canceled.succeed(success).unit)
        }
        _  <- UIO(subscriber.onSubscribe(s))
        _  <- stream.run(Sink.fail(boom)).catchAll(_ => UIO.unit)
        ro <- canceled.poll
        r  <- ro.fold(UIO.succeed[Result](failure))(identity)
      } yield r
    )

  private def e4 =
    unsafeRun(
      for {
        subStr               <- QueueSubscriber.make[Int](10)
        (subscriber, stream) = subStr
        canceled             <- Promise.make[Nothing, Result]
        runtime              <- ZIO.runtime[Any]
        s = new Subscription {
          override def request(n: Long): Unit = (0 until n.toInt).foreach(subscriber.onNext)
          override def cancel(): Unit         = runtime.unsafeRun(canceled.succeed(success).unit)
        }
        _  <- UIO(subscriber.onSubscribe(s))
        _  <- stream.drop(10).run(Sink.fail(boom)).catchAll(_ => UIO.unit)
        ro <- canceled.poll
        r  <- ro.fold(UIO.succeed[Result](failure))(identity)
      } yield r
    )

  private def e5 =
    unsafeRun(
      for {
        subStr               <- QueueSubscriber.make[Int](10)
        (subscriber, stream) = subStr
        fiber                <- stream.run(Sink.collectAll[Int]).fork
        _                    <- ZIO.sleep(Duration(100, MILLISECONDS))
        _                    <- fiber.interrupt
        canceled             <- Promise.make[Nothing, Result]
        runtime              <- ZIO.runtime[Any]
        s = new Subscription {
          override def request(n: Long): Unit = (0 until n.toInt).foreach(subscriber.onNext)
          override def cancel(): Unit         = runtime.unsafeRun(canceled.succeed(success).unit)
        }
        _  <- UIO(subscriber.onSubscribe(s))
        ro <- canceled.poll
        r  <- ro.fold(UIO.succeed[Result](failure))(identity)
      } yield r
    )

  private def e6 =
    unsafeRun(
      for {
        subStr               <- QueueSubscriber.make[Int](10)
        (subscriber, stream) = subStr
        fiber                <- stream.drop(10).run(Sink.collectAll[Int]).fork
        canceled             <- Promise.make[Nothing, Result]
        delivered            <- Promise.make[Nothing, Unit]
        runtime              <- ZIO.runtime[Any]
        s = new Subscription {
          override def request(n: Long): Unit = {
            (0 until n.toInt).foreach(subscriber.onNext)
            runtime.unsafeRun(delivered.succeed(()))
            ()
          }
          override def cancel(): Unit = runtime.unsafeRun(canceled.succeed(success).unit)
        }
        _  <- UIO(subscriber.onSubscribe(s))
        _  <- delivered.await
        _  <- fiber.interrupt
        ro <- canceled.poll
        r  <- ro.fold(UIO.succeed[Result](failure))(identity)
      } yield r
    )

}
