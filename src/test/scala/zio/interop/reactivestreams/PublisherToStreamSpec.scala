package zio.interop.reactivestreams

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.reactivestreams.tck.TestEnvironment
import org.reactivestreams.tck.TestEnvironment.ManualPublisher
import zio.Chunk
import zio.Exit
import zio.Promise
import zio.Task
import zio.UIO
import zio.ZIO
import zio.durationInt
import zio.stream.Sink
import zio.stream.Stream
import zio.test.Assertion._
import zio.test._

object PublisherToStreamSpec extends DefaultRunnableSpec {

  override def spec =
    suite("Converting a `Publisher` to a `Stream`")(
      test("works with a well behaved `Publisher`") {
        assertM(publish(seq, None))(succeeds(equalTo(seq)))
      },
      test("fails with an initially failed `Publisher`") {
        assertM(publish(Chunk.empty, Some(e)))(fails(equalTo(e)))
      },
      test("fails with an eventually failing `Publisher`") {
        assertM(publish(seq, Some(e)))(fails(equalTo(e)))
      },
      test("does not fail a fiber on failing `Publisher`") {
        val publisher = new Publisher[Int] {
          override def subscribe(s: Subscriber[_ >: Int]): Unit =
            s.onSubscribe(
              new Subscription {
                override def request(n: Long): Unit = s.onError(new Throwable("boom!"))
                override def cancel(): Unit         = ()
              }
            )
        }

//        val supervisor = Supervisor.runtimeStats
        for {
          runtime    <- ZIO.runtime[Any]
          testRuntime = runtime
//            .mapRuntimeConfig(_.copy(supervisor = supervisor))
          exit        = testRuntime.unsafeRun(publisher.toStream().runDrain.exit)
//          stats      <- supervisor.value
        } yield assert(exit)(fails(anything)) /* &&
          assert(stats.failures)(equalTo(0L))*/
      },
      test("does not freeze on stream end") {
        withProbe(probe =>
          for {
            fiber <- Stream
                       .fromZIO(
                         UIO(
                           probe.toStream()
                         )
                       )
                       .flatMap(identity)
                       .run(Sink.collectAll[Throwable, Int])
                       .fork
            _ <- Task.attemptBlockingInterrupt(probe.expectRequest())
            _ <- UIO(probe.sendNext(1))
            _ <- UIO(probe.sendCompletion)
            r <- fiber.join
          } yield assert(r)(equalTo(Chunk(1)))
        )
      } @@ TestAspect.timeout(1000.millis),
      test("cancels subscription when interrupted before subscription") {
        val tst =
          for {
            subscriberP    <- Promise.make[Nothing, Subscriber[_]]
            cancelledLatch <- Promise.make[Nothing, Unit]
            subscription = new Subscription {
                             override def request(x$1: Long): Unit = ()
                             override def cancel(): Unit           = cancelledLatch.unsafeDone(UIO.unit)
                           }
            probe = new Publisher[Int] {
                      override def subscribe(subscriber: Subscriber[_ >: Int]): Unit =
                        subscriberP.unsafeDone(UIO.succeedNow(subscriber))
                    }
            fiber      <- probe.toStream(bufferSize).run(Sink.drain).fork
            subscriber <- subscriberP.await
            _          <- fiber.interrupt
            _          <- UIO(subscriber.onSubscribe(subscription))
            _          <- cancelledLatch.await
          } yield ()
        assertM(tst.exit)(succeeds(anything))
      } @@ TestAspect.timeout(3.seconds),
      test("cancels subscription when interrupted after subscription") {
        withProbe(probe =>
          assertM((for {
            fiber <- probe.toStream(bufferSize).run(Sink.drain).fork
            _     <- Task.attemptBlockingInterrupt(probe.expectRequest())
            _     <- fiber.interrupt
            _     <- Task.attemptBlockingInterrupt(probe.expectCancelling())
          } yield ()).exit)(
            succeeds(isUnit)
          )
        )
      },
      test("cancels subscription when interrupted during consumption") {
        withProbe(probe =>
          assertM((for {
            fiber  <- probe.toStream(bufferSize).run(Sink.drain).fork
            demand <- Task.attemptBlockingInterrupt(probe.expectRequest())
            _      <- Task((1 to demand.toInt).foreach(i => probe.sendNext(i)))
            _      <- fiber.interrupt
            _      <- Task.attemptBlockingInterrupt(probe.expectCancelling())
          } yield ()).exit)(
            succeeds(isUnit)
          )
        )
      },
      test("cancels subscription on stream end") {
        withProbe(probe =>
          assertM((for {
            fiber  <- probe.toStream(bufferSize).take(1).run(Sink.drain).fork
            demand <- Task.attemptBlockingInterrupt(probe.expectRequest())
            _      <- Task((1 to demand.toInt).foreach(i => probe.sendNext(i)))
            _      <- Task.attemptBlockingInterrupt(probe.expectCancelling())
            _      <- fiber.join
          } yield ()).exit)(
            succeeds(isUnit)
          )
        )
      },
      test("cancels subscription on stream error") {
        withProbe(probe =>
          assertM((for {
            fiber  <- probe.toStream(bufferSize).mapZIO(_ => Task.fail(new Throwable("boom!"))).run(Sink.drain).fork
            demand <- Task.attemptBlockingInterrupt(probe.expectRequest())
            _      <- Task((1 to demand.toInt).foreach(i => probe.sendNext(i)))
            _      <- Task.attemptBlockingInterrupt(probe.expectCancelling())
            _      <- fiber.join
          } yield ()).exit)(
            fails(anything)
          )
        )
      }
    ) @@ TestAspect.nonFlaky

  val e: Throwable    = new RuntimeException("boom")
  val seq: Chunk[Int] = Chunk.fromIterable(List.range(0, 100))
  val bufferSize: Int = 10

  def withProbe[R, E0, E >: Throwable, A](f: ManualPublisher[Int] => ZIO[R, E, A]): ZIO[R, E, A] = {
    val testEnv = new TestEnvironment(3000, 500)
    val probe   = new ManualPublisher[Int](testEnv)
    f(probe) <* Task(testEnv.verifyNoAsyncErrorsNoDelay())
  }

  def publish(seq: Chunk[Int], failure: Option[Throwable]): UIO[Exit[Throwable, Chunk[Int]]] = {

    def loop(probe: ManualPublisher[Int], remaining: Chunk[Int]): Task[Unit] =
      for {
        n            <- Task.attemptBlockingInterrupt(probe.expectRequest())
        _            <- Task(assert(n.toInt)(isLessThanEqualTo(bufferSize)))
        split         = n.toInt
        (nextN, tail) = remaining.splitAt(split)
        _            <- Task(nextN.foreach(probe.sendNext))
        _ <- if (nextN.size < split)
               Task(failure.fold(probe.sendCompletion())(probe.sendError))
             else loop(probe, tail)
      } yield ()

    val faillable =
      withProbe(probe =>
        for {
          fiber <- probe.toStream(bufferSize).run(Sink.collectAll[Throwable, Int]).fork
          _     <- loop(probe, seq)
          r     <- fiber.join
        } yield r
      )

    faillable.exit
  }

}
