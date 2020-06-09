package zio.interop.reactivestreams

import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import org.reactivestreams.tck.{ SubscriberWhiteboxVerification, TestEnvironment }
import org.reactivestreams.tck.SubscriberWhiteboxVerification.{ SubscriberPuppet, WhiteboxSubscriberProbe }
import org.testng.annotations.Test
import zio.{ Chunk, Promise, Task, UIO, ZIO, ZManaged }
import zio.blocking._
import zio.clock.Clock
import zio.duration._
import zio.stream.{ Sink, ZSink }
import zio.test._
import zio.test.Assertion._
import zio.test.environment.Live

object SinkToSubscriberSpec extends DefaultRunnableSpec {
  override def spec =
    suite("Converting a `Sink` to a `Subscriber`")(
      testM("works on the happy path")(
        for {
          (publisher, subscribed, requested, canceled) <- makePublisherProbe
          fiber <- ZSink
                    .fold[Int, Chunk[Int]](Chunk.empty)(_.size < 5)(_ + _)
                    .map(_.toList)
                    .toSubscriber()
                    .use {
                      case (subscriber, r) => UIO(publisher.subscribe(subscriber)) *> r
                    }
                    .fork
          _ <- Live.live(
                assertM(subscribed.await.timeoutFail("timeout awaiting subscribe.")(500.millis).run)(succeeds(isUnit))
              )
          _ <- Live.live(
                assertM(requested.await.timeoutFail("timeout awaiting request.")(500.millis).run)(succeeds(isUnit))
              )
          _ <- Live.live(
                assertM(canceled.await.timeoutFail("timeout awaiting cancel.")(500.millis).run)(succeeds(isUnit))
              )
          r <- fiber.join.run
        } yield assert(r)(succeeds(equalTo(List(1, 2, 3, 4, 5))))
      ),
      testM("cancels subscription on interruption after subscription")(
        for {
          (publisher, subscribed, _, canceled) <- makePublisherProbe
          fiber <- Sink.drain
                    .toSubscriber()
                    .use { case (subscriber, _) => UIO(publisher.subscribe(subscriber)) *> UIO.never }
                    .fork
          _ <- Live.live(
                assertM(subscribed.await.timeoutFail("timeout awaiting subscribe.")(500.millis).run)(succeeds(isUnit))
              )
          _ <- fiber.interrupt
          _ <- Live.live(
                assertM(canceled.await.timeoutFail("timeout awaiting cancel.")(500.millis).run)(succeeds(isUnit))
              )
          r <- fiber.join.run
        } yield assert(r)(isInterrupted)
      ),
      testM("cancels subscription on interruption during consuption")(
        for {
          (publisher, subscribed, requested, canceled) <- makePublisherProbe
          fiber <- Sink.drain
                    .toSubscriber()
                    .use { case (subscriber, _) => UIO(publisher.subscribe(subscriber)) *> UIO.never }
                    .fork
          _ <- Live.live(
                assertM(subscribed.await.timeoutFail("timeout awaiting subscribe.")(500.millis).run)(succeeds(isUnit))
              )
          _ <- Live.live(
                assertM(requested.await.timeoutFail("timeout awaiting request.")(500.millis).run)(succeeds(isUnit))
              )
          _ <- fiber.interrupt
          _ <- Live.live(
                assertM(canceled.await.timeoutFail("timeout awaiting cancel.")(500.millis).run)(succeeds(isUnit))
              )
          r <- fiber.join.run
        } yield assert(r)(isInterrupted)
      ),
      suite("passes all required and optional TCK tests")(
        tests: _*
      )
    )

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
      subscriber_     <- Sink.collectAll[Int].toSubscriber[Clock]()
      (subscriber, _) = subscriber_
      sbv <- ZManaged.make {
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
