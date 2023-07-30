[//]: # (This file was autogenerated using `zio-sbt-website` plugin via `sbt generateReadme` command.)
[//]: # (So please do not edit it manually. Instead, change "docs/index.md" file or sbt setting keys)
[//]: # (e.g. "readmeDocumentation" and "readmeSupport".)

# ZIO Interop Reactive Streams

This library provides an interoperability layer between ZIO and reactive streams.

[![Production Ready](https://img.shields.io/badge/Project%20Stage-Production%20Ready-brightgreen.svg)](https://github.com/zio/zio/wiki/Project-Stages) ![CI Badge](https://github.com/zio/interop-reactive-streams/workflows/CI/badge.svg) [![Sonatype Releases](https://img.shields.io/nexus/r/https/oss.sonatype.org/dev.zio/zio-interop-reactivestreams_2.13.svg?label=Sonatype%20Release)](https://oss.sonatype.org/content/repositories/releases/dev/zio/zio-interop-reactivestreams_2.13/) [![Sonatype Snapshots](https://img.shields.io/nexus/s/https/oss.sonatype.org/dev.zio/zio-interop-reactivestreams_2.13.svg?label=Sonatype%20Snapshot)](https://oss.sonatype.org/content/repositories/snapshots/dev/zio/zio-interop-reactivestreams_2.13/) [![javadoc](https://javadoc.io/badge2/dev.zio/zio-interop-reactivestreams-docs_2.13/javadoc.svg)](https://javadoc.io/doc/dev.zio/zio-interop-reactivestreams-docs_2.13) [![ZIO Interop Reactive Streams](https://img.shields.io/github/stars/zio/interop-reactive-streams?style=social)](https://github.com/zio/interop-reactive-streams)

## Introduction

**ZIO** integrates with [Reactive Streams](http://reactive-streams.org) by providing conversions from `zio.stream.Stream` to `org.reactivestreams.Publisher` and from `zio.stream.Sink` to `org.reactivestreams.Subscriber` and vice versa. Simply import `import zio.interop.reactivestreams._` to make the conversions available.

## Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-interop-reactivestreams" % "2.0.1"
```

## Examples

First, let's get a few imports out of the way.

```scala
import org.reactivestreams.example.unicast._
import zio._
import zio.interop.reactivestreams._
import zio.stream._
```

We use the following `Publisher` and `Subscriber` for the examples:

```scala
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

```scala
val streamFromPublisher = publisher.toZIOStream(qSize = 16)
streamFromPublisher.run(Sink.collectAll[Integer])
```

### Subscriber to Sink

When running a `Stream` to a `Subscriber`, a side channel is needed for signalling failures.
For this reason `toZIOSink` returns a tuple of a callback and a `Sink`. The callback must be used to signal `Stream` failure. The type parameter on `toZIOSink` is the error type of *the Stream*.

```scala
val asSink = subscriber.toZIOSink[Throwable]
val failingStream = ZStream.range(3, 13) ++ ZStream.fail(new RuntimeException("boom!"))
ZIO.scoped {
  asSink.flatMap { case (signalError, sink) => // FIXME
    failingStream.run(sink).catchAll(signalError)
  }
}
```

### Stream to Publisher

```scala
val stream = Stream.range(3, 13)
stream.toPublisher.flatMap { publisher =>
  UIO(publisher.subscribe(subscriber))
}
```

### Sink to Subscriber

`toSubscriber` returns a `Subscriber` and an `IO` which completes with the result of running the `Sink` or the error if the `Publisher` fails.
A `Sink` used as a `Subscriber` buffers up to `qSize` elements. If possible, `qSize` should be a power of two for best performance. The default is 16.

```scala
val sink = Sink.collectAll[Integer]
ZIO.scoped {
  sink.toSubscriber(qSize = 16).flatMap { case (subscriber, result) => 
    UIO(publisher.subscribe(subscriber)) *> result
  }
}
```

## Documentation

Learn more on the [ZIO Interop Reactive Streams homepage](https://zio.dev/zio-interop-reactivestreams)!

## Contributing

For the general guidelines, see ZIO [contributor's guide](https://zio.dev/about/contributing).

## Code of Conduct

See the [Code of Conduct](https://zio.dev/about/code-of-conduct)

## Support

Come chat with us on [![Badge-Discord]][Link-Discord].

[Badge-Discord]: https://img.shields.io/discord/629491597070827530?logo=discord "chat on discord"
[Link-Discord]: https://discord.gg/2ccFBr4 "Discord"

## License

[License](LICENSE)
