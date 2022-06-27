package zio.interop.reactivestreams

import org.reactivestreams.Publisher
import org.reactivestreams.tck.{ PublisherVerification, TestEnvironment }
import org.testng.annotations.Test
import zio.ZIO
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test._

import java.lang.reflect.InvocationTargetException
import zio.Unsafe

object StreamToPublisherSpec extends ZIOSpecDefault {
  override def spec =
    suite("Converting a `Stream` to a `Publisher`")(
      suite("passes all required and optional TCK tests")(tests: _*)
    )

  def makePV(runtime: zio.Runtime[Any]) =
    new PublisherVerification[Int](new TestEnvironment(2000, 500), 2000L) {

      def createPublisher(elements: Long): Publisher[Int] =
        Unsafe.unsafeCompat { implicit unsafe =>
          runtime.unsafe
            .run(
              ZStream
                .unfold(elements)(n => if (n > 0) Some((1, n - 1)) else None)
                .toPublisher
            )
            .getOrThrowFiberFailure()
        }

      override def createFailedPublisher(): Publisher[Int] =
        Unsafe.unsafeCompat { implicit unsafe =>
          runtime.unsafe
            .run(
              ZStream
                .fail(new RuntimeException("boom!"))
                .map(_.asInstanceOf[Int])
                .toPublisher
            )
            .getOrThrowFiberFailure()
        }
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
                     .exit
            } yield assert(r)(succeeds(isUnit))
          )
      }
}
