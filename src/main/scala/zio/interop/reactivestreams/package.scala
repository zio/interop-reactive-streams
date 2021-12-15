package zio.interop

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import zio.IO
import zio.Promise
import zio.ZIO
import zio.ZManaged
import zio.ZTraceElement
import zio.stream.ZSink
import zio.stream.ZStream

package object reactivestreams {

  final implicit class streamToPublisher[R, E <: Throwable, O](private val stream: ZStream[R, E, O]) extends AnyVal {

    /** Create a `Publisher` from a `Stream`. Every time the `Publisher` is subscribed to, a new instance of the
      * `Stream` is run.
      */
    def toPublisher(implicit trace: ZTraceElement): ZIO[R, Nothing, Publisher[O]] =
      Adapters.streamToPublisher(stream)
  }

  final implicit class sinkToSubscriber[R, E <: Throwable, A, L, Z](private val sink: ZSink[R, E, A, L, Z]) {

    /** Create a `Subscriber` from a `Sink`. The returned IO will eventually return the result of running the subscribed
      * stream to the sink. Consumption is started as soon as the resource is used, even if the IO is never run.
      * Interruption propagates from the `Zmanaged` to the stream, but not from the IO.
      * @param qSize
      *   The size used as internal buffer. A maximum of `qSize-1` `A`s will be buffered, so `qSize` must be > 1. If
      *   possible, set to a power of 2 value for best performance.
      */
    def toSubscriber(qSize: Int = 16)(implicit
      trace: ZTraceElement
    ): ZManaged[R, Throwable, (Subscriber[A], IO[Throwable, Z])] =
      Adapters.sinkToSubscriber(sink, qSize)
  }

  final implicit class publisherToStream[O](private val publisher: Publisher[O]) extends AnyVal {

    /** @param qSize
      *   The size used as internal buffer. A maximum of `qSize-1` `A`s will be buffered, so `qSize` must be > 1. If
      *   possible, set to a power of 2 value for best performance.
      */
    def toStream(qSize: Int = 16)(implicit trace: ZTraceElement): ZStream[Any, Throwable, O] =
      Adapters.publisherToStream(publisher, qSize)
  }

  final implicit class subscriberToSink[I](private val subscriber: Subscriber[I]) extends AnyVal {

    /** Create a `Sink` from a `Subscriber`. Errors need to be transported via the returned Promise:
      *
      * ```
      * val subscriber: Subscriber[Int] = ???
      * val stream: Stream[Any, Throwable, Int] = ???
      * subscriber.toSink.use { case (error, sink) =>
      *   stream.run(sink).catchAll(e => error.fail(e))
      * }
      * ```
      */
    def toSink[E <: Throwable](implicit
      trace: ZTraceElement
    ): ZManaged[Any, Nothing, (Promise[E, Nothing], ZSink[Any, Nothing, I, I, Unit])] =
      Adapters.subscriberToSink(subscriber)
  }

}
