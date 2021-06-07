# Interop reactive streams

[![Project stage][Stage]][Stage-Page]
![CI][Badge-CI]
[![Releases][Badge-SonatypeReleases]][Link-SonatypeReleases]
[![Snapshots][Badge-SonatypeSnapshots]][Link-SonatypeSnapshots]

This library provides an interoperability layer for reactive streams.

## Reactive Streams `Producer` and `Subscriber`

**ZIO** integrates with [Reactive Streams](http://reactivestreams.org) by providing conversions from `zio.stream.Stream` to `org.reactivestreams.Publisher`
and from `zio.stream.Sink` to `org.reactivestreams.Subscriber` and vice versa. Simply import `import zio.interop.reactivestreams._` to make the
conversions available.

## Examples

First, let's get a few imports out of the way.

```scala mdoc:silent
import org.reactivestreams.example.unicast._
import zio._
import zio.interop.reactivestreams._
import zio.stream._

val runtime = new DefaultRuntime {}
```

We use the following `Publisher` and `Subscriber` for the examples: 

```scala mdoc
val publisher = new RangePublisher(3, 10)
val subscriber = new SyncSubscriber[Int] {
  override protected def whenNext(v: Int): Boolean = {
    print(s"$v, ")
    true
  }
}
```

### Publisher to Stream

A `Publisher` used as a `Stream` buffers up to `qSize` elements. If possible, `qSize` should be
a power of two for best performance. The default is 16.

```scala mdoc
val streamFromPublisher = publisher.toStream(qSize = 16)
runtime.unsafeRun(
  streamFromPublisher.run(Sink.collectAll[Integer])
)
```

### Subscriber to Sink

When running a `Stream` to a `Subscriber`, a side channel is needed for signalling failures.
For this reason `toSink` returns a tuple of `Promise` and `Sink`. The `Promise` must be failed
on `Stream` failure. The type parameter on `toSink` is the error type of *the Stream*. 

```scala mdoc
val asSink = subscriber.toSink[Throwable]
val failingStream = Stream.range(3, 13) ++ Stream.fail(new RuntimeException("boom!"))
runtime.unsafeRun(
  asSink.use { case (errorP, sink) =>
    failingStream.run(sink).catchAll(errorP.fail)
  }
)
```

### Stream to Publisher

```scala mdoc
val stream = Stream.range(3, 13)
runtime.unsafeRun(
  stream.toPublisher.flatMap { publisher =>
    UIO(publisher.subscribe(subscriber))
  }
)
```

### Sink to Subscriber

`toSubscriber` returns a `Subscriber` and an `IO` which completes with the result of running the 
`Sink` or the error if the `Publisher` fails.
A `Sink` used as a `Subscriber` buffers up to `qSize` elements. If possible, `qSize` should be
a power of two for best performance. The default is 16.

```scala mdoc
val sink = Sink.collectAll[Integer]
runtime.unsafeRun(
  sink.toSubscriber(qSize = 16).flatMap { case (subscriber, result) => 
    UIO(publisher.subscribe(subscriber)) *> result
  }
)
```

[Badge-CI]: https://github.com/zio/interop-reactive-streams/workflows/CI/badge.svg
[Badge-SonatypeReleases]: https://img.shields.io/nexus/r/https/oss.sonatype.org/dev.zio/zio-interop-reactivestreams_2.12.svg "Sonatype Releases"
[Badge-SonatypeSnapshots]: https://img.shields.io/nexus/s/https/oss.sonatype.org/dev.zio/zio-interop-reactivestreams_2.12.svg "Sonatype Snapshots"
[Link-Circle]: https://circleci.com/gh/zio/interop-reactive-streams/tree/master
[Link-SonatypeReleases]: https://oss.sonatype.org/content/repositories/releases/dev/zio/zio-interop-reactivestreams_2.12/ "Sonatype Releases"
[Link-SonatypeSnapshots]: https://oss.sonatype.org/content/repositories/snapshots/dev/zio/zio-interop-reactivestreams_2.12/ "Sonatype Snapshots"
[Stage]: https://img.shields.io/badge/Project%20Stage-Production%20Ready-brightgreen.svg
[Stage-Page]: https://github.com/zio/zio/wiki/Project-Stages
