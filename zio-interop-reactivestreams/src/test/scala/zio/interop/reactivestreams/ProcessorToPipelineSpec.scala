package zio.interop.reactivestreams

import zio.Chunk
import zio.UIO
import zio.ZIO
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test._
import scala.collection.mutable.ListBuffer
import java8.util.concurrent.SubmissionPublisher
import java8.util.concurrent.{ Flow => Flow8 }
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
      },
      test("passes through errors without offering them to the processor") {
        val processor = new TestProcessor((i: Int) => i.toString())
        val err       = new RuntimeException()

        val effect = (ZStream(1, 2) ++ ZStream.fail(err)).via(processor.asPipeline).runCollect

        for {
          result <- effect.exit
          events <- processor.getEvents
        } yield assert(result)(fails(equalTo(err))) &&
          assert(events)(
            equalTo(
              List(
                ProcessorEvent.OnSubscribe,
                ProcessorEvent.OnNext(1),
                ProcessorEvent.OnNext(2)
              )
            )
          )
      },
      test("passes through errors when converting to a raw channel") {
        val processor = new TestProcessor((i: Int) => i.toString())
        val err       = new RuntimeException()

        val effect = ((ZStream(1, 2) ++ ZStream.fail(err)).channel >>> processor.asChannel).runCollect

        for {
          result <- effect.exit
          events <- processor.getEvents
        } yield assert(result)(fails(equalTo(err))) &&
          assert(events)(
            equalTo(
              List(
                ProcessorEvent.OnSubscribe,
                ProcessorEvent.OnNext(1),
                ProcessorEvent.OnNext(2),
                ProcessorEvent.OnError(err)
              )
            )
          )
      }
    ) @@ TestAspect.withLiveClock

  sealed trait ProcessorEvent[+A]
  object ProcessorEvent {
    case object OnSubscribe                    extends ProcessorEvent[Nothing]
    final case class OnNext[A](item: A)        extends ProcessorEvent[A]
    final case class OnError(error: Throwable) extends ProcessorEvent[Nothing]
    case object OnComplete                     extends ProcessorEvent[Nothing]
  }

  final class TestProcessor[A, B](f: A => B) extends Flow.Processor[A, B] {

    private var subscription: Flow.Subscription = null
    private val submissionPublisher             = new SubmissionPublisher[B]()
    private val events                          = ListBuffer[ProcessorEvent[A]]()

    def onSubscribe(subscription: Flow.Subscription): Unit = {
      this.events += ProcessorEvent.OnSubscribe
      this.subscription = subscription;
      subscription.request(1);
    }

    def onNext(item: A): Unit = {
      this.events += ProcessorEvent.OnNext(item)
      submissionPublisher.submit(f(item));
      subscription.request(1);
    }

    def onError(error: Throwable): Unit = {
      this.events += ProcessorEvent.OnError(error)
      submissionPublisher.closeExceptionally(error);
    }

    def onComplete(): Unit = {
      this.events += ProcessorEvent.OnComplete
      submissionPublisher.close();
    }

    def getEvents: UIO[List[ProcessorEvent[A]]] =
      ZIO.succeed(this.events.toList)

    def subscribe(subscriber: Flow.Subscriber[_ >: B]): Unit =
      submissionPublisher.subscribe(new CompatSubscriber[B](subscriber))

    def asPipeline = Adapters.processorToPipeline(FlowAdapters.toProcessor[A, B](this))

    def asChannel = Adapters.processorToChannel(FlowAdapters.toProcessor[A, B](this))

  }

  final class CompatSubscriber[B](underlying: Flow.Subscriber[_ >: B]) extends Flow8.Subscriber[B] {
    def onSubscribe(subscription: Flow8.Subscription): Unit =
      underlying.onSubscribe(new CompatSubscription(subscription))

    def onNext(item: B): Unit = underlying.onNext(item)

    def onError(throwable: Throwable): Unit = underlying.onError(throwable)

    def onComplete(): Unit = underlying.onComplete()

  }

  final class CompatSubscription(underlying: Flow8.Subscription) extends Flow.Subscription {
    def request(n: Long): Unit =
      underlying.request(n)
    def cancel(): Unit =
      underlying.cancel()
  }
}
