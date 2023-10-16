package zio.interop.reactivestreams

import org.reactivestreams.tck.TestEnvironment
import org.reactivestreams.tck.TestEnvironment.ManualSubscriberWithSubscriptionSupport
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test.TestAspect.nonFlaky
import zio.test._
import zio.{ IO, UIO, ZIO, durationInt }

import scala.jdk.CollectionConverters._

object SubscriberToSinkSpec extends ZIOSpecDefault {
  override def spec =
    suite("Converting a `Subscriber` to a `Sink`")(
      test("works on the happy path") {
        makeSubscriber.flatMap(probe =>
          ZIO.scoped[Any] {
            probe.underlying
              .toZIOSink[Throwable]
              .flatMap { case (_, sink) =>
                for {
                  fiber      <- ZStream.fromIterable(seq).run(sink).fork
                  _          <- probe.request(length + 1)
                  elements   <- probe.nextElements(length).exit
                  completion <- probe.expectCompletion.exit
                  _          <- fiber.join
                } yield assert(elements)(succeeds(equalTo(seq))) && assert(completion)(succeeds(isUnit))
              }
          }
        )
      },
      test("transports errors") {
        makeSubscriber.flatMap(probe =>
          ZIO.scoped[Any] {
            probe.underlying
              .toZIOSink[Throwable]
              .flatMap { case (signalError, sink) =>
                for {
                  fiber    <- (ZStream.fromIterable(seq) ++ ZStream.fail(e)).run(sink).catchAll(signalError).fork
                  _        <- probe.request(length + 1)
                  elements <- probe.nextElements(length).exit
                  err      <- probe.expectError.exit
                  _        <- fiber.join
                } yield assert(elements)(succeeds(equalTo(seq))) && assert(err)(succeeds(equalTo(e)))
              }
          }
        )
      },
      test("transports errors 2") {
        makeSubscriber.flatMap(probe =>
          ZIO.scoped[Any] {
            probe.underlying
              .toZIOSink[Throwable]
              .flatMap { case (signalError, sink) =>
                for {
                  _   <- ZStream.fail(e).run(sink).catchAll(signalError)
                  err <- probe.expectError.exit
                } yield assert(err)(succeeds(equalTo(e)))
              }
          }
        )
      },
      test("transports errors 3") {
        makeSubscriber.flatMap { probe =>
          for {
            fiber <- ZIO.scoped {
                       probe.underlying
                         .toZIOSink[Throwable]
                         .flatMap { case (signalError, sink) =>
                           ZStream.fail(e).run(sink).catchAll(signalError)
                         }
                     }.fork
            _   <- fiber.join
            err <- probe.expectError.exit
          } yield assert(err)(succeeds(equalTo(e)))
        }
      } @@ nonFlaky(10),
      test("transports errors only once") {
        ZIO.scoped[Any] {
          for {
            probe              <- makeSubscriber
            ses                <- probe.underlying.toZIOSink
            (signalError, sink) = ses
            _                  <- ZStream.fail(e).run(sink).catchAll(signalError)
            err                <- probe.expectError.exit
            _                  <- signalError(e)
            err2               <- probe.expectError.timeout(100.millis).exit
          } yield assert(err)(succeeds(equalTo(e))) && assert(err2)(fails(anything))
        }
      },
      test("transports errors when transforming to channel") {
        makeSubscriber.flatMap(probe =>
          ZIO.scoped[Any] {
            val channel = probe.underlying.toSubscriberZIOChannel
            for {
              fiber    <- ((ZStream.fromIterable(seq) ++ ZStream.fail(e)).channel >>> channel).runDrain.fork
              _        <- ZIO.sleep(100.millis)
              _        <- probe.request(length + 1)
              elements <- probe.nextElements(length).exit
              err      <- probe.expectError.exit
              _        <- fiber.join
            } yield assert(elements)(succeeds(equalTo(seq))) && assert(err)(succeeds(equalTo(e)))
          }
        )
      } @@ TestAspect.withLiveClock
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
