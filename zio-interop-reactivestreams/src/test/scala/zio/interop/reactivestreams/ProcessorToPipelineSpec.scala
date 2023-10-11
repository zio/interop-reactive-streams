package zio.interop.reactivestreams

import zio.Chunk
import zio.UIO
import zio.ZIO
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test._
import scala.collection.mutable.ListBuffer
import java.util.concurrent.SubmissionPublisher
import java.util.concurrent.Flow
import org.reactivestreams.FlowAdapters

object ProcessorToPipelineSpec extends ZIOSpecDefault {

  override def spec =
    suite("Converting a `Processor` to a `Pipeline`")(
      test("works with a well behaved `Publisher`") {
        val processor = new TestProcessor((i: Int) => i.toString())

        val effect = ZStream(1, 2, 3, 4, 5).via(processor.asPipeline).runCollect

        for {
          result <- effect
          events <- processor.getEvents
        } yield assert(result)(equalTo(Chunk("1", "2", "3", "4", "5"))) &&
          assert(events)(
            equalTo(
              List(
                ProcessorEvent.OnSubscribe,
                ProcessorEvent.OnNext(1),
                ProcessorEvent.OnNext(2),
                ProcessorEvent.OnNext(3),
                ProcessorEvent.OnNext(4),
                ProcessorEvent.OnNext(5),
                ProcessorEvent.OnComplete
              )
            )
          )
      }
    ) @@ TestAspect.withLiveClock

  sealed trait ProcessorEvent[+A]
  object ProcessorEvent {
    final case object OnSubscribe              extends ProcessorEvent[Nothing]
    final case class OnNext[A](item: A)        extends ProcessorEvent[A]
    final case class OnError(error: Throwable) extends ProcessorEvent[Nothing]
    final case object OnComplete               extends ProcessorEvent[Nothing]
  }

  final class TestProcessor[A, B](f: A => B) extends SubmissionPublisher[B] with Flow.Processor[A, B] {

    private var subscription: Flow.Subscription = null
    private val events                          = ListBuffer[ProcessorEvent[A]]()

    def onSubscribe(subscription: Flow.Subscription): Unit = {
      this.events += ProcessorEvent.OnSubscribe
      this.subscription = subscription;
      subscription.request(1);
    }

    def onNext(item: A): Unit = {
      this.events += ProcessorEvent.OnNext(item)
      submit(f(item));
      subscription.request(1);
    }

    def onError(error: Throwable): Unit = {
      this.events += ProcessorEvent.OnError(error)
      closeExceptionally(error);
    }

    def onComplete(): Unit = {
      this.events += ProcessorEvent.OnComplete
      close();
    }

    def getEvents: UIO[List[ProcessorEvent[A]]] =
      ZIO.succeed(this.events.toList)

    def asPipeline = Adapters.processorToPipeline(FlowAdapters.toProcessor[A, B](this))
  }
}
