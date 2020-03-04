package zio.interop.reactivestreams

import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import org.reactivestreams.tck.{ SubscriberWhiteboxVerification, TestEnvironment }
import org.reactivestreams.tck.SubscriberWhiteboxVerification.{ SubscriberPuppet, WhiteboxSubscriberProbe }
import org.testng.annotations.Test
import zio.clock.Clock
import zio.blocking._
import zio.duration._
import zio.stream.Sink
import zio.test._
import zio.test.Assertion._
import zio.{ Promise, Task, UIO, ZIO, ZManaged }

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
            _ <- assertM(subscribed.await.timeoutFail("timeout awaiting subscribe.")(500.millis).run)(succeeds(isUnit))
            _ <- assertM(requested.await.timeoutFail("timeout awaiting request.")(500.millis).run)(succeeds(isUnit))
            _ <- assertM(canceled.await.timeoutFail("timeout awaiting cancel.")(500.millis).run)(succeeds(isUnit))
            r <- fiber.join.run
          } yield assert(r)(succeeds(equalTo(List(1, 2, 3, 4, 5))))
        ),
        testM("cancels subscription on interruption after subscription")(
          for {
            (publisher, subscribed, _, canceled) <- SinkToSubscriberTestUtil.makePublisherProbe
            fiber <- Sink.drain
                      .toSubscriber()
                      .use { case (subscriber, _) => UIO(publisher.subscribe(subscriber)) *> UIO.never }
                      .fork
            _ <- assertM(subscribed.await.timeoutFail("timeout awaiting subscribe.")(500.millis).run)(succeeds(isUnit))
            _ <- fiber.interrupt
            _ <- assertM(canceled.await.timeoutFail("timeout awaiting cancel.")(500.millis).run)(succeeds(isUnit))
            r <- fiber.join.run
          } yield assert(r)(isInterrupted)
        ),
        testM("cancels subscription on interruption during consuption")(
          for {
            (publisher, subscribed, requested, canceled) <- SinkToSubscriberTestUtil.makePublisherProbe
            fiber <- Sink.drain
                      .toSubscriber()
                      .use { case (subscriber, _) => UIO(publisher.subscribe(subscriber)) *> UIO.never }
                      .fork
            _ <- assertM(subscribed.await.timeoutFail("timeout awaiting subscribe.")(500.millis).run)(succeeds(isUnit))
            _ <- assertM(requested.await.timeoutFail("timeout awaiting request.")(500.millis).run)(succeeds(isUnit))
            _ <- fiber.interrupt
            _ <- assertM(canceled.await.timeoutFail("timeout awaiting cancel.")(500.millis).run)(succeeds(isUnit))
            r <- fiber.join.run
          } yield assert(r)(isInterrupted)
        ),
        testM("Does not fail a fiber on failing Publisher") {
          val publisher = new Publisher[Int] {
            override def subscribe(s: Subscriber[_ >: Int]): Unit =
              s.onSubscribe(
                new Subscription {
                  override def request(n: Long): Unit = s.onError(new Throwable("boom!"))
                  override def cancel(): Unit         = ()
                }
              )
          }
          ZIO.runtime[Any].map { runtime =>
            var fibersFailed = 0
            val testRuntime  = runtime.mapPlatform(_.withReportFailure(_ => fibersFailed += 1))
            val exit         = testRuntime.unsafeRun(publisher.toStream().runDrain.run)
            assert(exit)(fails(anything)) && assert(fibersFailed)(equalTo(0))
          }
        },
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

  case class ProbedSubscriber[A](underlying: Subscriber[A], probe: WhiteboxSubscriberProbe[A]) extends Subscriber[A] {
    override def onSubscribe(s: Subscription): Unit = {
      underlying.onSubscribe(s)
      probe.registerOnSubscribe(new SubscriberPuppet {
        override def triggerRequest(elements: Long): Unit = s.request(elements)
        override def signalCancel(): Unit                 = s.cancel()
      })
    }

    override def onNext(element: A): Unit = {
      underlying.onNext(element)
      probe.registerOnNext(element)
    }

    override def onError(cause: Throwable): Unit = {
      underlying.onError(cause)
      probe.registerOnError(cause)
    }

    override def onComplete(): Unit = {
      underlying.onComplete()
      probe.registerOnComplete()
    }
  }

  val managedVerification =
    for {
      (subscriber, _) <- Sink.collectAll[Int].toSubscriber[Clock]()
      sbv <- ZManaged
              .make[Clock, Throwable, (SubscriberWhiteboxVerification[Int], TestEnvironment)] {
                val env = new TestEnvironment(1000, 500)
                val sbv =
                  new SubscriberWhiteboxVerification[Int](env) {
                    override def createSubscriber(probe: WhiteboxSubscriberProbe[Int]): Subscriber[Int] =
                      ProbedSubscriber(subscriber, probe)
                    override def createElement(element: Int): Int = element
                  }
                UIO(sbv.setUp()) *> UIO(sbv.startPublisherExecutorService()).as((sbv, env))
              } {
                case (sbv, _) =>
                  UIO(sbv.shutdownPublisherExecutorService())
              }
    } yield sbv

  val tests =
    classOf[SubscriberWhiteboxVerification[Int]]
      .getMethods()
      .toList
      .filter { method =>
        method
          .getAnnotations()
          .exists(annotation => classOf[Test].isAssignableFrom(annotation.annotationType()))
      }
      .collect {
        case method if method.getName().startsWith("untested") =>
          test(method.getName())(assert(())(anything)) @@ TestAspect.ignore
        case method =>
          testM(method.getName())(
            for {
              r <- managedVerification.use {
                    case (sbv, env) =>
                      blocking(Task(method.invoke(sbv))).timeout(env.defaultTimeoutMillis().millis)
                  }.unit.run
            } yield assert(r)(succeeds(isUnit))
          )
      }

}
