package zio.interop.reactivestreams

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.reactivestreams.tck.TestEnvironment
import org.reactivestreams.tck.TestEnvironment.ManualPublisher
import zio.{ Exit, Task, UIO, ZIO }
import zio.duration._
import zio.stream.Sink
import zio.stream.Stream
import zio.test._
import zio.test.Assertion._

object PublisherToStreamSpec extends DefaultRunnableSpec {

  override def spec =
    suite("Converting a `Publisher` to a `Stream`")(
      testM("works with a well behaved `Publisher`") {
        assertM(publish(seq, None))(succeeds(equalTo(seq)))
      },
      testM("fails with an initially failed `Publisher`") {
        assertM(publish(Nil, Some(e)))(fails(equalTo(e)))
      },
      testM("fails with an eventually failing `Publisher`") {
        assertM(publish(seq, Some(e)))(fails(equalTo(e)))
      },
      testM("does not fail a fiber on failing `Publisher`") {
        val publisher = new Publisher[Int] {
          override def subscribe(s: Subscriber[_ >: Int]): Unit =
            s.onSubscribe(
              new Subscription {
                override def request(n: Long): Unit = s.onError(new Throwable("boom!"))
                override def cancel(): Unit         = ()
              }
            )
        }
        ZIO.runtime[Any].map { runtime =>
          var fibersFailed = 0
          val testRuntime  = runtime.mapPlatform(_.withReportFailure(e => if (!e.interruptedOnly) fibersFailed += 1))
          val exit         = testRuntime.unsafeRun(publisher.toStream().runDrain.run)
          assert(exit)(fails(anything)) && assert(fibersFailed)(equalTo(0))
        }
      },
      testM("does not freeze on stream end") {
        for {
          probe <- makeProbe
          fiber <- Stream
                    .fromEffect(
                      UIO(
                        probe.toStream()
                      )
                    )
                    .flatMap(identity)
                    .run(Sink.collectAll[Int])
                    .fork
          _ <- Task(probe.expectRequest())
          _ <- UIO(probe.sendNext(1))
          _ <- UIO(probe.sendCompletion)
          r <- fiber.join
        } yield assert(r)(equalTo(List(1)))
      } @@ TestAspect.timeout(1000.millis),
      testM("cancels subscription when interrupted before subscription") {
        for {
          probe <- makeProbe
          fiber <- probe.toStream(bufferSize).run(Sink.drain).fork
          _     <- fiber.interrupt
          r     <- Task(probe.expectCancelling()).run
        } yield assert(r)(succeeds(isUnit))
      },
      testM("cancels subscription when interrupted after subscription") {
        assertM((for {
          probe <- makeProbe
          fiber <- probe.toStream(bufferSize).run(Sink.drain).fork
          _     <- Task(probe.expectRequest())
          _     <- fiber.interrupt
          _     <- Task(probe.expectCancelling())
        } yield ()).run)(
          succeeds(isUnit)
        )
      },
      testM("cancels subscription when interrupted during consumption") {
        assertM((for {
          probe  <- makeProbe
          fiber  <- probe.toStream(bufferSize).run(Sink.drain).fork
          demand <- Task(probe.expectRequest())
          _      <- Task((1 to demand.toInt).foreach(i => probe.sendNext(i)))
          _      <- fiber.interrupt
          _      <- Task(probe.expectCancelling())
        } yield ()).run)(
          succeeds(isUnit)
        )
      }
    )

  val e: Throwable    = new RuntimeException("boom")
  val seq: List[Int]  = List.range(0, 100)
  val bufferSize: Int = 10

  val testEnv: TestEnvironment             = new TestEnvironment(1000)
  val makeProbe: UIO[ManualPublisher[Int]] = UIO(new ManualPublisher[Int](testEnv))

  def publish(seq: List[Int], failure: Option[Throwable]): UIO[Exit[Throwable, List[Int]]] = {

    def loop(probe: ManualPublisher[Int], remaining: List[Int], pending: Int): Task[Unit] =
      for {
        n             <- Task(probe.expectRequest())
        _             <- Task(assert(n.toInt + pending)(isLessThanEqualTo(bufferSize)))
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
