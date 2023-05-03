---
id: index
title: "Introduction to ZIO Interop Reactive Streams"
sidebar_label: "ZIO Interop Reactive Streams"
---

This library provides an interoperability layer between ZIO and reactive streams.

@PROJECT_BADGES@

## Introduction

**ZIO** integrates with [Reactive Streams](http://reactive-streams.org) by providing conversions from `zio.stream.Stream` to `org.reactivestreams.Publisher` and from `zio.stream.Sink` to `org.reactivestreams.Subscriber` and vice versa. Simply import `import zio.interop.reactivestreams._` to make the conversions available.

## Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-interop-reactive-streams" % "@VERSION@"
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

### Channel that outputs to a Subscriber

`ZChannel.toSubscriber` creates a channel that outputs to a `Subscriber`. The upstream can fail with any `Throwable`, which will be signaled to the subscriber's `onError` method and cause the channel to fail with `Some(throwable)`. If the subscriber cancels its subscription, the channel fails with `None`.

To use the channel as the destination for a stream, one method is to use `pipeThroughChannel` to get the effect of signalling the subscriber, and `runDrain` to run the resulting stream.

```scala
val subscriberChannel = ZChannel.toSubscriber(subscriber)
val failingStream = ZStream.range(3, 13) ++ ZStream.fail(new RuntimeException("boom!"))
failingStream.pipeThroughChannel(subscriberChannel).runDrain
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
