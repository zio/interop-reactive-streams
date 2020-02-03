package zio.interop.reactiveStreams

import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import zio.internal.PlatformLive
import zio.test._
import zio.test.Assertion._
import zio.ZManaged

object SinkToSubscriberFiberFailureSpec extends AbstractRunnableSpec {

  type Environment = Unit
  type Label       = String
  type Test        = Any
  type Failure     = Any
  type Success     = Any

  var fibersFailed = 0

  val runner: TestRunner[Environment, Label, Test, Failure, Success] =
    new TestRunner[Environment, Label, Any, Failure, Success](
      TestExecutor.managed[Environment, Test, Label, Success](ZManaged.unit),
      PlatformLive.makeDefault().withReportFailure(_ => fibersFailed += 1)
    ) {}

  val spec: ZSpec[Environment, Failure, Label, Test] =
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
      for {
        exit <- publisher.toStream().runDrain.run
      } yield assert(exit, fails(anything)) &&
        assert(fibersFailed, equalTo(0))
    }
}
