package zio.interop.reactivestreams

import zio._
import zio.test._
import zio.stream._
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

object ChannelToSubscriberSpec extends ZIOSpecDefault {

  private class TestSubscriber(initialRequest: Int = 1, onNextRequest: Int = 1) extends Subscriber[Int] {

    protected var subscription: Subscription = _
    private var subscribed                   = false
    private var values                       = Chunk.empty[Int]
    private var error                        = Option.empty[Throwable]
    private var complete                     = false

    override def onSubscribe(s: Subscription): Unit = {
      subscription = s
      subscribed = true
      s.request(initialRequest.toLong)
    }

    override def onError(t: Throwable): Unit =
      error = Some(t)

    override def onComplete(): Unit =
      complete = true

    override def onNext(t: Int): Unit = {
      values = values :+ t
      subscription.request(onNextRequest.toLong)
    }

    final def getState: UIO[(Boolean, Chunk[Int], Option[Throwable], Boolean)] =
      ZIO.succeed((subscribed, values, error, complete))
  }
  override def spec = suite("Channel writing to a subscriber spec")(
    test("works with a basic subscriber") {
      val subscriber = new TestSubscriber(100, 1)
      val channel: ZChannel[Any, Throwable, Chunk[Int], Any, Unit, Nothing, Unit] =
        ZChannel.toSubscriber(subscriber)
      val input                               = ZStream(1, 2, 3).concat(ZStream(100, 200))
      val stream: ZStream[Any, Unit, Nothing] = input.pipeThroughChannel(channel)
      for {
        expected <- input.runCollect
        _        <- ZIO.succeed(println("start"))
        _        <- stream.runDrain
        actual   <- subscriber.getState
      } yield {
        val (subscribe, values, error, complete) = actual
        assertTrue(values == expected && subscribe && error.isEmpty && complete)
      }
    },
    test("works with limited subscriber demand") {
      val subscriber = new TestSubscriber(2, 1)
      val channel: ZChannel[Any, Throwable, Chunk[Int], Any, Unit, Nothing, Unit] =
        ZChannel.toSubscriber(subscriber)
      val input                               = ZStream(1, 2, 3, 4, 5, 6)
      val stream: ZStream[Any, Unit, Nothing] = input.pipeThroughChannel(channel)
      for {
        expected <- input.runCollect
        _        <- stream.runDrain
        actual   <- subscriber.getState
      } yield {
        val (subscribe, values, error, complete) = actual
        assertTrue(values == expected && subscribe && error.isEmpty && complete)
      }
    },
    test("signals upstream errors to the subscriber") {
      val subscriber = new TestSubscriber(1, 1)
      val channel: ZChannel[Any, Throwable, Chunk[Int], Any, Unit, Nothing, Unit] =
        ZChannel.toSubscriber(subscriber)
      val exception = new IllegalStateException("boom")
      val input     = ZStream(1, 2, 3)
      val stream: ZStream[Any, Unit, Nothing] =
        input.concat(ZStream.fail(exception)).concat(ZStream(100, 200)).pipeThroughChannel(channel)
      for {
        expected <- input.runCollect
        _        <- stream.runDrain
        actual   <- subscriber.getState
      } yield {
        val (subscribe, values, error, complete) = actual
        assertTrue(values == expected && subscribe && error.contains(exception) && !complete)
      }
    },
    test("reports cancellation by the subscriber") {
      val subscriber = new TestSubscriber(1, 1) {
        private var count = 0
        override def onNext(t: Int): Unit = {
          count += 1
          if (count > 2)
            subscription.cancel()
          else
            super.onNext(t)
        }
      }
      val channel: ZChannel[Any, Throwable, Chunk[Int], Any, Unit, Nothing, Unit] =
        ZChannel.toSubscriber(subscriber)
      val input                               = ZStream(1, 2, 3, 4, 5)
      val stream: ZStream[Any, Unit, Nothing] = input.pipeThroughChannel(channel)
      for {
        expected   <- input.take(2).runCollect
        errorValue <- stream.runDrain.flip
        actual     <- subscriber.getState
      } yield {
        val (subscribe, values, error, complete) = actual
        assertTrue(values == expected && subscribe && error.isEmpty && !complete && errorValue == ())
      }
    }
  )
}
