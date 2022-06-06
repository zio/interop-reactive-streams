# Interop reactive streams

[![Project stage][Stage]][Stage-Page]
![CI][Badge-CI]
[![Releases][Badge-SonatypeReleases]][Link-SonatypeReleases]
[![Snapshots][Badge-SonatypeSnapshots]][Link-SonatypeSnapshots]

This library provides an interoperability layer between ZIO and reactive streams.

## Reactive Streams `Producer` and `Subscriber`

**ZIO** integrates with [Reactive Streams](http://reactivestreams.org) by providing various conversions:

| from | to                               |
| :--- |:---------------------------------|
| `zio.stream.ZStream` | `org.reactivestreams.Publisher`  |
| `zio.ZIO` | `org.reactivestreams.Publisher`  |
| `zio.stream.Sink` | `org.reactivestreams.Subscriber` |
| `org.reactivestreams.Publisher` | `zio.stream.ZStream`             |
| `org.reactivestreams.Subscriber` | `zio.stream.ZSink`               |
| `org.reactivestreams.Subscriber` | `zio.stream.ZChannel`            |

Simply import `import zio.interop.reactivestreams._` to make the conversions available.

## Examples

First, let's get a few imports out of the way.

```scala mdoc:silent
import org.reactivestreams.example.unicast._
import zio._
import zio.interop.reactivestreams._
import zio.stream._

val runtime = Runtime.default
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
val streamFromPublisher = publisher.toZIOStream(qSize = 16)
runtime.unsafeRun(
  streamFromPublisher.run(Sink.collectAll[Integer])
)
```

### Subscriber to Sink

When running a `Stream` to a `Subscriber`, a side channel is needed for signalling failures.
For this reason `toZIOSink` returns a tuple of a callback and a `Sink`. The callback must be used to signal `Stream` failure. The type parameter on `toZIOSink` is the error type of *the Stream*. 

```scala mdoc
val asSink = subscriber.toZIOSink[Throwable]
val failingStream = ZStream.range(3, 13) ++ ZStream.fail(new RuntimeException("boom!"))
runtime.unsafeRun(
  asSink.use { case (signalError, sink) =>
    failingStream.run(sink).catchAll(signalError)
  }
)
```

### Subscriber to Channel

A `Subscriber` can be converted into a `ZChannel` that supports `Throwable` as input error. Converting a `Subscriber` into a `ZChannel` may be convenient if the input `ZStream` can output throwables. In that case, no additional side channel for signalling failures is needed.

```scala mdoc
val asChannel = subscriber.toZIOChannel
val stream    = ZStream.range(3, 13) ++ ZStream.fail(new Exception("boom"))

val exit = runtime.unsafeRun(
  asChannel.flatMap { channel =>
    stream.pipeThroughChannel(channel).runDrain.exit
  }
)
println(exit)

An exception is passed to `Subscriber.onError` and is also reflected in the exit value when running the stream.
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

### ZIO to Publisher

It is also possible to publish a single `ZIO`. In that case, the publisher emits at most one value.

```scala mdoc
val z = ZIO.succeed(1)
runtime.unsafeRun(
  z.toPublisher.flatMap { publisher =>
    ZIO.succeed(publisher.subscribe(subscriber))
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
