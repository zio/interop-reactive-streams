package zio.interop.reactiveStreams

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.stream.testkit.TestSubscriber
import org.specs2.specification.AfterAll
import org.specs2.specification.core.SpecStructure
import zio.stream.Stream
import zio.{ BaseCrossPlatformSpec, Task, UIO }

class SubscriberToSinkSpec extends BaseCrossPlatformSpec with AfterAll {

  def is: SpecStructure =
    "SubscriberToSinkSpec".title ^ s2"""
   Check if a `Subscriber` converted to a `Sink` correctly
     works on the happy path $e1
     transports errors $e2
    """
  implicit private val system: ActorSystem             = ActorSystem()
  implicit private val materializer: ActorMaterializer = ActorMaterializer()

  override def afterAll(): Unit =
    unsafeRun(UIO(materializer.shutdown()) *> Task.fromFuture(_ => system.terminate()).unit)

  private val seq = List.range(0, 100)
  private val e   = new RuntimeException("boom")

  private val e1 = {
    unsafeRun(
      for {
        subSeqF       <- UIO(Source.asSubscriber[Int].toMat(Sink.seq)(Keep.both).run())
        (sub, seqF)   = subSeqF
        errorSink     <- sub.toSink[Throwable]
        (error, sink) = errorSink
        _             <- Stream.fromIterable(seq).run(sink).catchAll(t => error.fail(t)).fork
        r             <- Task.fromFuture(_ => seqF)
      } yield r must_== seq
    )
  }

  private val e2 =
    unsafeRun(
      for {
        probe         <- UIO(TestSubscriber.manualProbe[Int]())
        errorSink     <- probe.toSink[Throwable]
        (error, sink) = errorSink
        _             <- Stream.fromIterable(seq).++(Stream.fail(e)).run(sink).catchAll(t => error.fail(t)).fork
        subscription  <- Task(probe.expectSubscription())
        _             <- UIO(subscription.request(101))
        _             <- Task(probe.expectNextN(seq))
        _             <- Task(probe.expectError(e))
      } yield success
    )
}
