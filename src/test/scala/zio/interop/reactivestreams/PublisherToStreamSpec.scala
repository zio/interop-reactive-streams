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
        withProbe(probe =>
          for {
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
        )
      } @@ TestAspect.timeout(1000.millis),
      testM("cancels subscription when interrupted before subscription") {
        withProbe(probe =>
          for {
            fiber <- probe.toStream(bufferSize).run(Sink.drain).fork
            _     <- fiber.interrupt
            r     <- Task(probe.expectCancelling()).run
          } yield assert(r)(succeeds(isUnit))
        )
      },
      testM("cancels subscription when interrupted after subscription") {
        withProbe(probe =>
          assertM((for {
            fiber <- probe.toStream(bufferSize).run(Sink.drain).fork
            _     <- Task(probe.expectRequest())
            _     <- fiber.interrupt
            _     <- Task(probe.expectCancelling())
          } yield ()).run)(
            succeeds(isUnit)
          )
        )
      },
      testM("cancels subscription when interrupted during consumption") {
        withProbe(probe =>
          assertM((for {
            fiber  <- probe.toStream(bufferSize).run(Sink.drain).fork
            demand <- Task(probe.expectRequest())
            _      <- Task((1 to demand.toInt).foreach(i => probe.sendNext(i)))
            _      <- fiber.interrupt
            _      <- Task(probe.expectCancelling())
          } yield ()).run)(
            succeeds(isUnit)
          )
        )
      },
      testM("cancels subscription on stream end") {
        withProbe(probe =>
          assertM((for {
            fiber  <- probe.toStream(bufferSize).take(1).run(Sink.drain).fork
            demand <- Task(probe.expectRequest())
            _      <- Task((1 to demand.toInt).foreach(i => probe.sendNext(i)))
            _      <- Task(probe.expectCancelling())
            _      <- fiber.join
          } yield ()).run)(
            succeeds(isUnit)
          )
        )
      },
      testM("cancels subscription on stream error") {
        withProbe(probe =>
          assertM((for {
            fiber  <- probe.toStream(bufferSize).mapM(_ => Task.fail(new Throwable("boom!"))).run(Sink.drain).fork
            demand <- Task(probe.expectRequest())
            _      <- Task((1 to demand.toInt).foreach(i => probe.sendNext(i)))
            _      <- Task(probe.expectCancelling())
            _      <- fiber.join
          } yield ()).run)(
            fails(anything)
          )
        )
      }
    )

  val e: Throwable    = new RuntimeException("boom")
  val seq: List[Int]  = List.range(0, 100)
  val bufferSize: Int = 10

  def withProbe[R, E0, E >: Throwable, A](f: ManualPublisher[Int] => ZIO[R, E, A]): ZIO[R, E, A] = {
    val testEnv = new TestEnvironment(2000, 500)
    val probe   = new ManualPublisher[Int](testEnv)
    f(probe) <* Task(testEnv.verifyNoAsyncErrorsNoDelay()).mapError { t => t.setStackTrace(Array.empty); t }
  }

  def publish(seq: List[Int], failure: Option[Throwable]): UIO[Exit[Throwable, List[Int]]] = {

    def loop(probe: ManualPublisher[Int], remaining: List[Int]): Task[Unit] =
      for {
        n             <- Task(probe.expectRequest())
        _             <- Task(assert(n.toInt)(isLessThanEqualTo(bufferSize)))
        split         = n.toInt
        (nextN, tail) = remaining.splitAt(split)
        _             <- Task(nextN.foreach(probe.sendNext))
        _ <- if (nextN.size < split) Task(failure.fold(probe.sendCompletion())(probe.sendError))
            else loop(probe, tail)
      } yield ()

    val faillable =
      withProbe(probe =>
        for {
          fiber <- probe.toStream(bufferSize).run(Sink.collectAll[Int]).fork
          _     <- loop(probe, seq)
          r     <- fiber.join
        } yield r
      )

    faillable.run
  }

}
