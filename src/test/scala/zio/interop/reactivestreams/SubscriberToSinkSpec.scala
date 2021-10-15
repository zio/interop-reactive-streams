package zio.interop.reactivestreams

import org.reactivestreams.tck.TestEnvironment
import org.reactivestreams.tck.TestEnvironment.ManualSubscriberWithSubscriptionSupport
import zio.IO
import zio.Task
import zio.UIO
import zio.stream.Stream
import zio.test.Assertion._
import zio.test._

import scala.jdk.CollectionConverters._

object SubscriberToSinkSpec extends DefaultRunnableSpec {
  override def spec =
    suite("Converting a `Subscriber` to a `Sink`")(
      test("works on the happy path") {
        makeSubscriber.flatMap(probe =>
          probe.underlying
            .toSink[Throwable]
            .use { case (_, sink) =>
              for {
                fiber      <- Stream.fromIterable(seq).run(sink).fork
                _          <- probe.request(length + 1)
                elements   <- probe.nextElements(length).exit
                completion <- probe.expectCompletion.exit
                _          <- fiber.join
              } yield assert(elements)(succeeds(equalTo(seq))) && assert(completion)(succeeds(isUnit))
            }
        )
      },
      test("transports errors") {
        makeSubscriber.flatMap(probe =>
          probe.underlying
            .toSink[Throwable]
            .use { case (error, sink) =>
              for {
                fiber    <- (Stream.fromIterable(seq) ++ Stream.fail(e)).run(sink).catchAll(t => error.fail(t)).fork
                _        <- probe.request(length + 1)
                elements <- probe.nextElements(length).exit
                err      <- probe.expectError.exit
                _        <- fiber.join
              } yield assert(elements)(succeeds(equalTo(seq))) && assert(err)(succeeds(equalTo(e)))
            }
        )
      }
    )

  val seq: List[Int] = List.range(0, 31)
  val length: Long   = seq.length.toLong
  val e: Throwable   = new RuntimeException("boom")

  case class Probe[T](underlying: ManualSubscriberWithSubscriptionSupport[T]) {
    def request(n: Long): UIO[Unit] =
      UIO(underlying.request(n))
    def nextElements(n: Long): IO[Throwable, List[T]] =
      Task.attemptBlockingInterrupt(underlying.nextElements(n.toLong).asScala.toList)
    def expectError: IO[Throwable, Throwable] =
      Task.attemptBlockingInterrupt(underlying.expectError(classOf[Throwable]))
    def expectCompletion: IO[Throwable, Unit] =
      Task.attemptBlockingInterrupt(underlying.expectCompletion())
  }

  val makeSubscriber = UIO(new ManualSubscriberWithSubscriptionSupport[Int](new TestEnvironment(2000))).map(Probe.apply)

}
