package zio.interop.reactiveStreams

import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import org.reactivestreams.tck.{ SubscriberBlackboxVerification, TestEnvironment }
import zio.clock.Clock
import zio.duration._
import zio.stream.Sink
import zio.test._
import zio.test.Assertion._
import zio.ZIO
import zio.Promise
import zio.UIO
import zio.ZManaged
import zio.Task
import zio.blocking._
import org.testng.annotations.Test

object SinkToSubscriberSpec
    extends DefaultRunnableSpec(
      suite("Converting a `Sink` to a `Subscriber`")(
        testM("works on the happy path")(
          for {
            (publisher, subscribed, requested, canceled) <- SinkToSubscriberTestUtil.makePublisherProbe
            fiber <- Sink
                      .collectAllN[Int](5)
                      .toSubscriber()
                      .use {
                        case (subscriber, r) => UIO(publisher.subscribe(subscriber)) *> r
                      }
                      .fork
            _ <- assertM(subscribed.await.timeoutFail("timeout awaiting subscribe.")(500.millis).run, succeeds(isUnit))
            _ <- assertM(requested.await.timeoutFail("timeout awaiting request.")(500.millis).run, succeeds(isUnit))
            _ <- assertM(canceled.await.timeoutFail("timeout awaiting cancel.")(500.millis).run, succeeds(isUnit))
            r <- fiber.join.run
          } yield assert(r, succeeds(equalTo(List(1, 2, 3, 4, 5))))
        ),
        testM("cancels subscription on interruption after subscription")(
          for {
            (publisher, subscribed, _, canceled) <- SinkToSubscriberTestUtil.makePublisherProbe
            fiber <- Sink.drain
                      .toSubscriber()
                      .use { case (subscriber, _) => UIO(publisher.subscribe(subscriber)) *> UIO.never }
                      .fork
            _ <- assertM(subscribed.await.timeoutFail("timeout awaiting subscribe.")(500.millis).run, succeeds(isUnit))
            _ <- fiber.interrupt
            _ <- assertM(canceled.await.timeoutFail("timeout awaiting cancel.")(500.millis).run, succeeds(isUnit))
            r <- fiber.join.run
          } yield assert(r, isInterrupted)
        ),
        testM("cancels subscription on interruption during consuption")(
          for {
            (publisher, subscribed, requested, canceled) <- SinkToSubscriberTestUtil.makePublisherProbe
            fiber <- Sink.drain
                      .toSubscriber()
                      .use { case (subscriber, _) => UIO(publisher.subscribe(subscriber)) *> UIO.never }
                      .fork
            _ <- assertM(subscribed.await.timeoutFail("timeout awaiting subscribe.")(500.millis).run, succeeds(isUnit))
            _ <- assertM(requested.await.timeoutFail("timeout awaiting request.")(500.millis).run, succeeds(isUnit))
            _ <- fiber.interrupt
            _ <- assertM(canceled.await.timeoutFail("timeout awaiting cancel.")(500.millis).run, succeeds(isUnit))
            r <- fiber.join.run
          } yield assert(r, isInterrupted)
        ),
        suite("passes all required and optional TCK tests")(
          SinkToSubscriberTestUtil.tests: _*
        )
      )
    )

object SinkToSubscriberTestUtil {

  val makePublisherProbe =
    for {
      runtime    <- ZIO.runtime[Clock]
      subscribed <- Promise.make[Nothing, Unit]
      requested  <- Promise.make[Nothing, Unit]
      canceled   <- Promise.make[Nothing, Unit]
      publisher = new Publisher[Int] {
        override def subscribe(s: Subscriber[_ >: Int]): Unit = {
          s.onSubscribe(
            new Subscription {
              override def request(n: Long): Unit = {
                runtime.unsafeRun(requested.succeed(()).unit)
                (1 to n.toInt).foreach(s.onNext(_))
              }
              override def cancel(): Unit =
                runtime.unsafeRun(canceled.succeed(()).unit)
            }
          )
          runtime.unsafeRun(subscribed.succeed(()).unit)
        }
      }
    } yield (publisher, subscribed, requested, canceled)

  val managedVerification =
    for {
      (subscriber, _) <- Sink.collectAll[Int].toSubscriber[Clock]()
      sbv <- ZManaged
              .make[Clock, Throwable, (SubscriberBlackboxVerification[Int], TestEnvironment)] {
                val env = new TestEnvironment(1000, 500)
                val sbv =
                  new SubscriberBlackboxVerification[Int](env) {
                    override def createSubscriber(): Subscriber[Int] = subscriber
                    override def createElement(element: Int): Int    = element
                  }
                UIO(sbv.setUp()) *> UIO(sbv.startPublisherExecutorService()).as((sbv, env))
              } {
                case (sbv, _) =>
                  UIO(sbv.shutdownPublisherExecutorService())
              }
    } yield sbv

  val tests =
    classOf[SubscriberBlackboxVerification[Int]]
      .getMethods()
      .toList
      .filter { method =>
        method
          .getAnnotations()
          .exists(annotation => classOf[Test].isAssignableFrom(annotation.annotationType()))
      }
      .collect {
        case method if method.getName().startsWith("untested") =>
          test(method.getName())(assert((), anything)) @@ TestAspect.ignore
        case method =>
          testM(method.getName())(
            for {
              r <- managedVerification.use {
                    case (sbv, env) =>
                      blocking(Task(method.invoke(sbv))).timeout(env.defaultTimeoutMillis().millis)
                  }.unit.run
            } yield assert(r, succeeds(isUnit))
          )
      }

}
