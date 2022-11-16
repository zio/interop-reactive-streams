---
id: index
title: "Introduction to ZIO Interop Reactive Streams"
sidebar_label: "ZIO Interop Reactive Streams"
---

This library provides an interoperability layer between ZIO and reactive streams.

## Reactive Streams `Producer` and `Subscriber`

**ZIO** integrates with [Reactive Streams](http://reactive-streams.org) by providing conversions from `zio.stream.Stream` to `org.reactivestreams.Publisher`
and from `zio.stream.Sink` to `org.reactivestreams.Subscriber` and vice versa. Simply import `import zio.interop.reactivestreams._` to make the
conversions available.

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
