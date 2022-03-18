package zio.interop.reactivestreams

import org.reactivestreams.Publisher
import org.reactivestreams.tck.PublisherVerification
import org.reactivestreams.tck.TestEnvironment
import org.testng.annotations.Test
import zio.Task
import zio.UIO
import zio.ZIO
import zio.stream.Stream
import zio.test.Assertion._
import zio.test._

import java.lang.reflect.InvocationTargetException

object StreamToPublisherSpec extends DefaultRunnableSpec {
  override def spec =
    suite("Converting a `Stream` to a `Publisher`")(
      suite("passes all required and optional TCK tests")(tests: _*)
    )

  def makePV(runtime: zio.Runtime[Any]) =
    new PublisherVerification[Int](new TestEnvironment(2000, 500), 2000L) {

      def createPublisher(elements: Long): Publisher[Int] =
        runtime.unsafeRun(
          Stream
            .unfold(elements)(n => if (n > 0) Some((1, n - 1)) else None)
            .toPublisher
        )

      override def createFailedPublisher(): Publisher[Int] =
        runtime.unsafeRun(
          Stream
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
              _       <- UIO.succeed(pv.setUp())
              r <- Task
                     .attemptBlockingInterrupt(method.invoke(pv))
                     .unit
                     .refineOrDie { case e: InvocationTargetException => e.getTargetException() }
                     .exit
            } yield assert(r)(succeeds(isUnit))
          )
      }
}
