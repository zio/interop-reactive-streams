package zio.interop

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import zio.{ Scope, UIO, Task, ZIO, Trace, URIO }
import zio.stream.ZSink
import zio.stream.ZStream
import org.reactivestreams.Processor
import zio.stream.ZPipeline
import zio.stream.ZChannel
import zio.Chunk

package object reactivestreams {

  final implicit class streamToPublisher[R, E <: Throwable, O](private val stream: ZStream[R, E, O]) extends AnyVal {

    /** Create a `Publisher` from a `Stream`. Every time the `Publisher` is subscribed to, a new instance of the
      * `Stream` is run.
      */
    def toPublisher(implicit trace: Trace): ZIO[R, Nothing, Publisher[O]] =
      Adapters.streamToPublisher(stream)
  }

  final implicit class sinkToSubscriber[R, E <: Throwable, A, L, Z](private val sink: ZSink[R, E, A, L, Z]) {

    /** Create a `Subscriber` from a `Sink`. The returned Task will eventually return the result of running the
      * subscribed stream to the sink. Consumption is started immediately, even if the Task is never run. Interruption
      * propagates from this ZIO to the stream, but not from the Task.
      * @param qSize
      *   The size used as internal buffer. If possible, set to a power of 2 value for best performance.
      */
    def toSubscriber(qSize: Int = 16)(implicit
      trace: Trace
    ): ZIO[R with Scope, Throwable, (Subscriber[A], Task[Z])] =
      Adapters.sinkToSubscriber(sink, qSize)
  }

  final implicit class publisherToStream[O](private val publisher: Publisher[O]) extends AnyVal {

    /** Create a `Stream` from a `Publisher`.
      * @param qSize
      *   The size used as internal buffer. If possible, set to a power of 2 value for best performance.
      */
    def toZIOStream(qSize: Int = 16)(implicit trace: Trace): ZStream[Any, Throwable, O] =
      Adapters.publisherToStream(publisher, qSize)

    def toPublisherZIOChannel(bufferSize: Int = 16)(implicit
      trace: Trace
    ): ZChannel[Any, Any, Any, Any, Throwable, Chunk[O], Any] =
      Adapters.publisherToChannel(publisher, bufferSize)
  }

  final implicit class subscriberToSink[I](private val subscriber: Subscriber[I]) extends AnyVal {

    /** Create a `Sink` from a `Subscriber`. Errors need to be transported via the returned callback:
      *
      * ```
      * val subscriber: Subscriber[Int] = ???
      * val stream: Stream[Any, Throwable, Int] = ???
      * subscriber.toZIOSink.use { case (signalError, sink) =>
      *   stream.run(sink).catchAll(signalError)
      * }
      * ```
      */
    def toZIOSink[E <: Throwable](implicit
      trace: Trace
    ): ZIO[Scope, Nothing, (E => UIO[Unit], ZSink[Any, Nothing, I, I, Unit])] =
      Adapters.subscriberToSink(subscriber)

    def toSubscriberZIOChannel(implicit trace: Trace): ZChannel[Any, Throwable, Chunk[I], Any, Any, Any, Any] =
      Adapters.subscriberToChannel(subscriber)
  }

  final implicit class processorToPipeline[I, O](private val processor: Processor[I, O]) extends AnyVal {

    def toZIOPipeline(implicit trace: Trace): ZPipeline[Any, Throwable, I, O] =
      Adapters.processorToPipeline(processor)

    def toProcessorZIOChannel(implicit
      trace: Trace
    ): ZChannel[Any, Throwable, Chunk[I], Any, Throwable, Chunk[O], Any] =
      Adapters.processorToChannel(processor)
  }

  final implicit class pipelineToProcessor[R <: Scope, I, O](private val pipeline: ZPipeline[R, Throwable, I, O])
      extends AnyVal {

    def toProcessor(implicit trace: Trace): URIO[R, Processor[I, O]] =
      Adapters.pipelineToProcessor(pipeline)
  }
}
