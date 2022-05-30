package zio.interop.reactivestreams

import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import org.reactivestreams.tck.PublisherVerification.PublisherTestRun
import org.reactivestreams.tck.{ PublisherVerification, TestEnvironment }
import org.testng.SkipException
import org.testng.annotations.Test
import zio.{ Promise, ZIO }
import zio.test.Assertion._
import zio.test._

import java.lang.reflect.InvocationTargetException

object ZioToPublisherSpec extends ZIOSpecDefault {
  override def spec =
    suite("Converting a `ZIO` to a `Publisher`")(
      suite("passes all required and optional TCK tests that are applicable to streams of length 1")(tests: _*),
      test("interrupts evaluation on cancellation") {
        for {
          interruptedPromise  <- Promise.make[Nothing, Unit]
          subscriptionPromise <- Promise.make[Nothing, Subscription]
          z                    = ZIO.never.as(0).onInterrupt(interruptedPromise.complete(ZIO.succeed(())))
          publisher           <- z.toPublisher
          subscriber = new Subscriber[Int] {
                         override def onSubscribe(s: Subscription): Unit =
                           subscriptionPromise.unsafeDone(ZIO.succeed(s))
                         override def onNext(t: Int): Unit        = ???
                         override def onError(t: Throwable): Unit = ???
                         override def onComplete(): Unit          = ???
                       }
          _            <- ZIO.succeed(publisher.subscribe(subscriber))
          subscription <- subscriptionPromise.await
          _            <- ZIO.succeed(subscription.cancel())
          unit         <- interruptedPromise.await
        } yield {
          assert(unit)(equalTo(()))
        }
      }
    )

  def makePV(runtime: zio.Runtime[Any]) =
    new PublisherVerification[Int](new TestEnvironment(2000, 500), 2000L) {

      override def maxElementsFromPublisher(): Long = 1

      override def activePublisherTest(
        elements: Long,
        completionSignalRequired: Boolean,
        body: PublisherTestRun[Int]
      ): Unit =
        if (elements < 1) {
          throw new SkipException(
            String.format(
              "Unable to run this test, as required elements nr: %d is lower than supported by given producer: %d",
              elements,
              1
            )
          );
        } else {
          super.activePublisherTest(elements, completionSignalRequired, body)
        }

      override def optionalActivePublisherTest(
        elements: Long,
        completionSignalRequired: Boolean,
        body: PublisherTestRun[Int]
      ): Unit =
        if (elements < 1) {
          throw new SkipException(
            String.format(
              "Unable to run this test, as required elements nr: %d is lower than supported by given producer: %d",
              elements,
              1
            )
          );
        } else {
          super.optionalActivePublisherTest(elements, completionSignalRequired, body)
        }

      def createPublisher(elements: Long): Publisher[Int] =
        if (elements == 1) {
          runtime.unsafeRun(
            ZIO.succeed(1).toPublisher
          )
        } else {
          throw new IllegalArgumentException("Only publishers for one value are possible.")
        }

      override def createFailedPublisher(): Publisher[Int] =
        runtime.unsafeRun(
          ZIO
            .fail(new RuntimeException("boom!"))
            .map(_.asInstanceOf[Int])
            .toPublisher
        )
    }

  val tests =
    classOf[PublisherVerification[Int]]
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
              runtime <- ZIO.runtime[Any]
              pv       = makePV(runtime)
              _       <- ZIO.succeed(pv.setUp())
              r <- ZIO
                     .attemptBlockingInterrupt(method.invoke(pv))
                     .unit
                     .refineOrDie { case e: InvocationTargetException => e.getTargetException() }
                     .catchSome { case _: SkipException => ZIO.succeed(()) }
                     .exit
            } yield assert(r)(succeeds(isUnit))
          )
      }
}
