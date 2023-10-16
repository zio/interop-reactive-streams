package zio.interop.reactivestreams

import zio.test.Assertion._
import zio.test._
import org.reactivestreams.tck.IdentityProcessorVerification
import org.testng.annotations.Test
import zio._
import zio.stream.ZPipeline
import org.reactivestreams.tck
import zio.Unsafe.unsafe
import org.reactivestreams.Processor
import java.util.concurrent.Executors
import java.lang.reflect.InvocationTargetException
import org.testng.SkipException

object PipelineToProcessorSpec extends ZIOSpecDefault {

  override def spec =
    suite("Converting a `Pipeline` to a `Processor`")(
      suite("passes all required and optional TCK tests")(
        tckTests: _*
      )
    )

  val managedVerification =
    for {
      runtime  <- ZIO.runtime[Scope]
      executor <- ZIO.succeed(Executors.newFixedThreadPool(4))
      _        <- ZIO.addFinalizer(ZIO.succeed(executor.shutdown()))
      env       = new tck.TestEnvironment(1000, 500)
      ver = new IdentityProcessorVerification[Int](env) {
              override def createIdentityProcessor(
                bufferSize: Int
              ): Processor[Int, Int] =
                unsafe { implicit u =>
                  runtime.unsafe.run(Adapters.pipelineToProcessor(ZPipeline.identity[Int], bufferSize)).getOrThrow()
                }

              override def createElement(n: Int): Int = n

              override def createFailedPublisher() = null

              override def publisherExecutorService() = executor

              override def maxSupportedSubscribers() = 1

              override def boundedDepthOfOnNextAndRequestRecursion() = 1
            }
      _ <- ZIO.succeed(ver.setUp())
    } yield ver

  val tckTests =
    classOf[IdentityProcessorVerification[Int]]
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
            ZIO.scoped[Any] {
              for {
                ver <- managedVerification
                r <- ZIO
                       .attemptBlockingInterrupt(method.invoke(ver))
                       .unit
                       .refineOrDie { case e: InvocationTargetException => e.getTargetException() }
                       .exit
              } yield assert(r)(fails(isSubtype[SkipException](anything)) || succeeds(isUnit))
            }
          )
      }
}
