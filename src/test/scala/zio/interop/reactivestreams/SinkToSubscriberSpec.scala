package zio.interop.reactivestreams

import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import org.reactivestreams.tck.SubscriberWhiteboxVerification.{ SubscriberPuppet, WhiteboxSubscriberProbe }
import org.reactivestreams.tck.{ SubscriberWhiteboxVerification, TestEnvironment }
import org.testng.annotations.Test
import zio.{ Chunk, Promise, Unsafe, ZIO, durationInt, durationLong }
import zio.stream.ZSink
import zio.test.Assertion._
import zio.test._

object SinkToSubscriberSpec extends ZIOSpecDefault {
  override def spec =
    suite("Converting a `Sink` to a `Subscriber`")(
      test("works on the happy path")(
        for {
          tuple                                       <- makePublisherProbe
          (publisher, subscribed, requested, canceled) = tuple
          fiber <- ZIO.scoped {
                     ZSink
                       .fold[Int, Chunk[Int]](Chunk.empty)(_.size < 5)(_ :+ _)
                       .map(_.toList)
                       .toSubscriber()
                       .flatMap { case (subscriber, r) =>
                         ZIO.succeed(publisher.subscribe(subscriber)) *> r
                       }
                   }.fork
          _ <-
            Live.live(
              assertZIO(subscribed.await.timeoutFail("timeout awaiting subscribe.")(500.millis).exit)(succeeds(isUnit))
            )
          _ <- Live.live(
                 assertZIO(requested.await.timeoutFail("timeout awaiting request.")(500.millis).exit)(succeeds(isUnit))
               )
          _ <- Live.live(
                 assertZIO(canceled.await.timeoutFail("timeout awaiting cancel.")(500.millis).exit)(succeeds(isUnit))
               )
          r <- fiber.join.exit
        } yield assert(r)(succeeds(equalTo(List(1, 2, 3, 4, 5))))
      ),
      test("cancels subscription on interruption after subscription")(
        for {
          tuple                               <- makePublisherProbe
          (publisher, subscribed, _, canceled) = tuple
          fiber <- ZIO.scoped {
                     ZSink
                       .foreachChunk[Any, Throwable, Int](_ => ZIO.yieldNow)
                       .toSubscriber()
                       .flatMap { case (subscriber, _) => ZIO.succeed(publisher.subscribe(subscriber)) *> ZIO.never }
                   }.fork
          _ <-
            Live.live(
              assertZIO(subscribed.await.timeoutFail("timeout awaiting subscribe.")(500.millis).exit)(succeeds(isUnit))
            )
          _ <- fiber.interrupt
          _ <- Live.live(
                 assertZIO(canceled.await.timeoutFail("timeout awaiting cancel.")(500.millis).exit)(succeeds(isUnit))
               )
          r <- fiber.join.exit
        } yield assert(r)(isInterrupted)
      ),
      test("cancels subscription on interruption during consuption")(
        for {
          tuple                                       <- makePublisherProbe
          (publisher, subscribed, requested, canceled) = tuple
          fiber <- ZIO.scoped {
                     ZSink
                       .foreachChunk[Any, Throwable, Int](_ => ZIO.yieldNow)
                       .toSubscriber()
                       .flatMap { case (subscriber, _) =>
                         ZIO.attemptBlockingInterrupt(publisher.subscribe(subscriber)) *> ZIO.never
                       }
                   }.fork
          _ <- assertZIO(subscribed.await.exit)(succeeds(isUnit))
          _ <- assertZIO(requested.await.exit)(succeeds(isUnit))
          _ <- fiber.interrupt
          _ <- assertZIO(canceled.await.exit)(succeeds(isUnit))
          r <- fiber.join.exit
        } yield assert(r)(isInterrupted)
      ),
      suite("passes all required and optional TCK tests")(
        tests: _*
      )
    )

  val makePublisherProbe =
    for {
      subscribed <- Promise.make[Nothing, Unit]
      requested  <- Promise.make[Nothing, Unit]
      canceled   <- Promise.make[Nothing, Unit]
      publisher = new Publisher[Int] {
                    override def subscribe(s: Subscriber[_ >: Int]): Unit = {
                      s.onSubscribe(
                        new Subscription {
                          override def request(n: Long): Unit = {
                            Unsafe.unsafeCompat { implicit u =>
                              requested.unsafe.done(ZIO.unit)
                            }
                            (1 to n.toInt).foreach(s.onNext(_))
                          }
                          override def cancel(): Unit =
                            Unsafe.unsafeCompat { implicit u =>
                              canceled.unsafe.done(ZIO.unit)
                            }
                        }
                      )

                      Unsafe.unsafeCompat { implicit u =>
                        subscribed.unsafe.done(ZIO.unit)
                      }
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
      subscriber_    <- ZSink.collectAll[Int].toSubscriber()
      (subscriber, _) = subscriber_
      sbv <- ZIO.acquireRelease {
               val env = new TestEnvironment(1000, 500)
               val sbv =
                 new SubscriberWhiteboxVerification[Int](env) {
                   override def createSubscriber(probe: WhiteboxSubscriberProbe[Int]): Subscriber[Int] =
                     ProbedSubscriber(subscriber, probe)
                   override def createElement(element: Int): Int = element
                 }
               ZIO.succeed(sbv.setUp()) *> ZIO.succeed(sbv.startPublisherExecutorService()).as((sbv, env))
             } { case (sbv, _) =>
               ZIO.succeed(sbv.shutdownPublisherExecutorService())
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
          test(method.getName())(
            for {
              r <- ZIO.scoped {
                     managedVerification.flatMap { case (sbv, env) =>
                       ZIO.attemptBlockingInterrupt(method.invoke(sbv)).timeout(env.defaultTimeoutMillis().millis)
                     }.unit.exit
                   }
            } yield assert(r)(succeeds(isUnit))
          )
      }

}
