package zio.interop.reactivestreams

import org.reactivestreams.tck.TestEnvironment
import org.reactivestreams.tck.TestEnvironment.ManualSubscriberWithSubscriptionSupport
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test.TestAspect.nonFlaky
import zio.test._
import zio.{ IO, UIO, ZIO, durationInt }

import java.util.concurrent.CancellationException
import scala.jdk.CollectionConverters._

object SubscriberToChannelSpec extends ZIOSpecDefault {
  override def spec =
    suite("Converting a `Subscriber` to a `Channel`")(
      test("works on the happy path") {
        makeSubscriber.flatMap { probe =>
          probe.underlying.toZIOChannel.flatMap { channel =>
            for {
              fiber      <- ZStream.fromIterable(seq).pipeThroughChannel(channel).runDrain.fork
              _          <- probe.request(length + 1)
              elements   <- probe.nextElements(length).exit
              completion <- probe.expectCompletion.exit
              _          <- fiber.join
            } yield assert(elements)(succeeds(equalTo(seq))) && assert(completion)(succeeds(isUnit))
          }
        }
      },
      test("works on the happy path 2") {
        makeSubscriber.flatMap { probe =>
          probe.underlying.toZIOChannel.flatMap { channel =>
            for {
              fiber      <- ZStream.fromIterable(seq).pipeThroughChannel(channel).runDrain.fork
              _          <- probe.request(length)
              elements   <- probe.nextElements(length).exit
              completion <- probe.expectCompletion.exit
              _          <- fiber.join
            } yield assert(elements)(succeeds(equalTo(seq))) && assert(completion)(succeeds(isUnit))
          }
        }
      },
      test("transports errors") {
        makeSubscriber.flatMap { probe =>
          probe.underlying.toZIOChannel.flatMap { channel =>
            for {
              fiber    <- (ZStream.fromIterable(seq) ++ ZStream.fail(e)).pipeThroughChannel(channel).runDrain.fork
              _        <- probe.request(length + 1)
              elements <- probe.nextElements(length).exit
              err      <- probe.expectError.exit
              exit     <- fiber.await
            } yield assert(elements)(succeeds(equalTo(seq))) && assert(err)(succeeds(equalTo(e))) && assert(exit)(
              fails(equalTo(e))
            )
          }
        }
      },
      test("transports errors 2") {
        makeSubscriber.flatMap { probe =>
          probe.underlying.toZIOChannel.flatMap { channel =>
            for {
              fiber <- ZStream.fail(e).pipeThroughChannel(channel).runDrain.fork
              err   <- probe.expectError.exit
              exit  <- fiber.await
            } yield assert(err)(succeeds(equalTo(e))) && assert(exit)(fails(equalTo(e)))
          }
        }
      },
      test("transports errors 3") {
        makeSubscriber.flatMap { probe =>
          for {
            fiber <- probe.underlying.toZIOChannel.flatMap { channel =>
                       ZStream.fail(e).pipeThroughChannel(channel).runDrain
                     }.fork
            exit <- fiber.await
            err  <- probe.expectError.exit
          } yield assert(err)(succeeds(equalTo(e))) && assert(exit)(fails(equalTo(e)))
        }
      } @@ nonFlaky(10),
      test("transports errors only once") {
        ZIO.scoped[Any] {
          for {
            probe   <- makeSubscriber
            channel <- probe.underlying.toZIOChannel
            _       <- ZStream.fail(e).pipeThroughChannel(channel).runDrain.fork
            err     <- probe.expectError.exit
            err2    <- probe.expectError.timeout(100.millis).exit
          } yield assert(err)(succeeds(equalTo(e))) && assert(err2)(fails(anything))
        }
      },
      test("cancellation causes a CancellationException") {
        for {
          probe   <- makeSubscriber
          channel <- probe.underlying.toZIOChannel
          fiber   <- ZStream.never.pipeThroughChannel(channel).runDrain.fork
          _        = probe.underlying.cancel()
          exit    <- fiber.await
        } yield assert(exit)(fails(isSubtype[CancellationException](anything)))
      },
      test("interruption causes an InterruptedException") {
        for {
          probe   <- makeSubscriber
          channel <- probe.underlying.toZIOChannel
          fiber   <- ZStream.never.pipeThroughChannel(channel).runDrain.fork
          _       <- fiber.interrupt
          exit    <- probe.expectError.exit
        } yield assert(exit)(fails(isSubtype[InterruptedException](anything)))
      }
    )

  val seq: List[Int] = List.range(0, 31)
  val length: Long   = seq.length.toLong
  val e: Throwable   = new RuntimeException("boom")

  case class Probe[T](underlying: ManualSubscriberWithSubscriptionSupport[T]) {
    def request(n: Long): UIO[Unit] =
      ZIO.succeed(underlying.request(n))
    def nextElements(n: Long): IO[Throwable, List[T]] =
      ZIO.attemptBlockingInterrupt(underlying.nextElements(n.toLong).asScala.toList)
    def expectError: IO[Throwable, Throwable] =
      ZIO.attemptBlockingInterrupt(underlying.expectError(classOf[Throwable]))
    def expectCompletion: IO[Throwable, Unit] =
      ZIO.attemptBlockingInterrupt(underlying.expectCompletion())
  }

  val makeSubscriber =
    ZIO.succeed(new ManualSubscriberWithSubscriptionSupport[Int](new TestEnvironment(2000))).map(Probe.apply)

}
