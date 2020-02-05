package zio.interop.reactivestreams

import org.reactivestreams.tck.TestEnvironment
import org.reactivestreams.tck.TestEnvironment.ManualPublisher
import zio.{ Exit, Task, UIO }
import zio.interop.reactivestreams.PublisherToStreamSpecUtil._
import zio.stream.Sink
import zio.test._
import zio.test.Assertion._

object PublisherToStreamSpec
    extends DefaultRunnableSpec(
      suite("Converting a `Publisher` to a `Stream`")(
        testM("works with a well behaved `Publisher`") {
          assertM(publish(seq, None), succeeds(equalTo(seq)))
        },
        testM("fails with an initially failed `Publisher`") {
          assertM(publish(Nil, Some(e)), fails(equalTo(e)))
        },
        testM("fails with an eventually failing `Publisher`") {
          assertM(publish(seq, Some(e)), fails(equalTo(e)))
        },
        testM("cancels subscription when interrupted before subscription") {
          for {
            probe <- makeProbe
            fiber <- probe.toStream(bufferSize).run(Sink.drain).fork
            _     <- fiber.interrupt
            r     <- Task(probe.expectCancelling()).run
          } yield assert(r, succeeds(isUnit))
        },
        testM("cancels subscription when interrupted after subscription") {
          assertM(
            (for {
              probe <- makeProbe
              fiber <- probe.toStream(bufferSize).run(Sink.drain).fork
              _     <- Task(probe.expectRequest())
              _     <- fiber.interrupt
              _     <- Task(probe.expectCancelling())
            } yield ()).run,
            succeeds(isUnit)
          )
        },
        testM("cancels subscription when interrupted during consumption") {
          assertM(
            (for {
              probe  <- makeProbe
              fiber  <- probe.toStream(bufferSize).run(Sink.drain).fork
              demand <- Task(probe.expectRequest())
              _      <- Task((1 to demand.toInt).foreach(i => probe.sendNext(i)))
              _      <- fiber.interrupt
              _      <- Task(probe.expectCancelling())
            } yield ()).run,
            succeeds(isUnit)
          )
        }
      )
    )

object PublisherToStreamSpecUtil {

  val e: Throwable    = new RuntimeException("boom")
  val seq: List[Int]  = List.range(0, 100)
  val bufferSize: Int = 10

  val testEnv: TestEnvironment             = new TestEnvironment(1000)
  val makeProbe: UIO[ManualPublisher[Int]] = UIO(new ManualPublisher[Int](testEnv))

  def publish(seq: List[Int], failure: Option[Throwable]): UIO[Exit[Throwable, List[Int]]] = {

    def loop(probe: ManualPublisher[Int], remaining: List[Int], pending: Int): Task[Unit] =
      for {
        n             <- Task(probe.expectRequest())
        _             <- Task(assert(n.toInt + pending, isLessThanEqualTo(bufferSize)))
        half          = n.toInt / 2 + 1
        (nextN, tail) = remaining.splitAt(half)
        _             <- Task(nextN.foreach(probe.sendNext))
        _ <- if (nextN.size < half) Task(failure.fold(probe.sendCompletion())(probe.sendError))
            else loop(probe, tail, n.toInt - half)
      } yield ()

    val faillable =
      for {
        probe <- makeProbe
        fiber <- probe.toStream(bufferSize).run(Sink.collectAll[Int]).fork
        _     <- loop(probe, seq, 0)
        r     <- fiber.join
      } yield r

    faillable.run
  }

}
