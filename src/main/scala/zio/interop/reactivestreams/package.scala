package zio.interop

import org.reactivestreams.{ Publisher, Subscriber }
import zio.stream.{ ZSink, ZStream }
import zio.{ IO, Promise, UIO, ZIO, ZManaged }

package object reactivestreams {

  final implicit class streamToPublisher[R, E <: Throwable, A](private val stream: ZStream[R, E, A]) extends AnyVal {

    /**
     * Create a `Publisher` from a `Stream`. Every time the `Publisher` is subscribed to, a new instance of the `Stream`
     * is run.
     */
    def toPublisher: ZIO[R, Nothing, Publisher[A]] =
      Adapters.streamToPublisher(stream)
  }

  final implicit class sinkToSubscriber[R, E <: Throwable, A0, A, B](private val sink: ZSink[R, E, A0, A, B])
      extends AnyVal {

    /**
     * Create a `Subscriber` from a `Sink`. The returned IO will eventually return the result of running the subscribed
     * stream to the sink. Consumption is started as soon as the resource is used, even if the IO is never run. Interruption
     * propagates from the `Zmanaged` to the stream, but not from the IO.
     * @param qSize The size used as internal buffer. A maximum of `qSize-1` `A`s will be buffered, so `qSize` must be > 1.
     *              If possible, set to a power of 2 value for best performance.
     *
     */
    def toSubscriber[R1 <: R](qSize: Int = 16): ZManaged[R1, Throwable, (Subscriber[A], IO[Throwable, B])] =
      Adapters.sinkToSubscriber(sink, qSize)
  }

  final implicit class publisherToStream[A](private val publisher: Publisher[A]) extends AnyVal {

    /**
     * @param qSize The size used as internal buffer. A maximum of `qSize-1` `A`s will be buffered, so `qSize` must be > 1.
     *              If possible, set to a power of 2 value for best performance.
     */
    def toStream(qSize: Int = 16): ZStream[Any, Throwable, A] =
      Adapters.publisherToStream(publisher, qSize)
  }

  final implicit class subscriberToSink[A](private val subscriber: Subscriber[A]) extends AnyVal {

    /**
     * Create a `Sink` from a `Subscriber`. Errors need to be transported via the returned Promise:
     *
     * ```
     * val subscriber: Subscriber[Int] = ???
     * val stream: Stream[Any, Throwable, Int] = ???
     * for {
     *   sinkError <- subscriberToSink(subscriber)
     *   (error, sink) = sinkError
     *   _ <- stream.run(sink).catchAll(e => error.fail(e)).fork
     * } yield ()
     * ```
     */
    def toSink[E <: Throwable]: UIO[(Promise[E, Nothing], ZSink[Any, E, Unit, A, Unit])] =
      Adapters.subscriberToSink(subscriber)
  }

}
