package zio.interop.reactiveStreams

import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import zio.internal.PlatformLive
import zio.test._
import zio.test.Assertion._
import zio.test.environment.TestEnvironment
import zio.UIO

object SinkToSubscriberFiberFailureSpecUtil {

  var fibersFailed = 0

  object Runner
      extends TestRunner[TestEnvironment, String, Any, Any, Any](
        TestExecutor.managed(TestEnvironment.Value),
        PlatformLive.makeDefault().withReportFailure(_ => fibersFailed += 1)
      ) {}

}

object SinkToSubscriberFiberFailureSpec
    extends RunnableSpec(SinkToSubscriberFiberFailureSpecUtil.Runner)(
      testM("Does not fail a fiber on failing Publisher") {
        val publisher = new Publisher[Int] {
          override def subscribe(s: Subscriber[_ >: Int]): Unit =
            s.onSubscribe(
              new Subscription {
                override def request(n: Long): Unit = s.onError(new Throwable("boom!"))
                override def cancel(): Unit         = ()
              }
            )
        }
        assertM(publisher.toStream().runDrain.run, fails(anything)) *>
          assertM(UIO(SinkToSubscriberFiberFailureSpecUtil.fibersFailed), equalTo(0))
      }
    )
