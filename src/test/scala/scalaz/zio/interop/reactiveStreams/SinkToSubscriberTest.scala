package zio.interop.reactiveStreams

import org.reactivestreams.Subscriber
import org.reactivestreams.tck.{ SubscriberBlackboxVerification, TestEnvironment }
import org.reactivestreams.tck.flow.support.TestException
import org.scalatest.FunSuite
import zio.clock.Clock
import zio.DefaultRuntime
import zio.duration._
import zio.internal.PlatformLive
import zio.IO
import zio.stream.Sink
import zio.Task
import zio.UIO
import zio.ZManaged
import org.reactivestreams.Publisher
import zio.ZIO
import zio.Promise
import org.reactivestreams.Subscription

class SinkToSubscriberTest extends FunSuite with DefaultRuntime {

  override val Platform = PlatformLive.Default.withReportFailure(_ => ())

  val env = new TestEnvironment(1000, 500)

  test("cancels subscription on interruption during subscription") {
    val test =
      for {
        runtime    <- ZIO.runtime[Clock]
        subscribed <- Promise.make[Nothing, Unit]
        canceled   <- Promise.make[Nothing, Unit]
        publisher = new Publisher[Int] {
          override def subscribe(s: Subscriber[_ >: Int]): Unit = {
            runtime.unsafeRun(subscribed.succeed(()).unit)
            s.onSubscribe(
              new Subscription {
                override def request(n: Long): Unit =
                  (1 to n.toInt).foreach(s.onNext(_))
                override def cancel(): Unit = runtime.unsafeRun(canceled.succeed(()).unit)
              }
            )
          }
        }
        fiber <- Sink.drain.toSubscriber()(s => UIO(publisher.subscribe(s))).fork
        _     <- subscribed.await.timeoutFail("timeout awaiting subscription.")(500.millis)
        _     <- fiber.interrupt
        _     <- canceled.await.timeoutFail("timeout awaiting cancellation.")(500.millis)
      } yield succeed
    unsafeRun(test)
  }

  test("cancels subscription on interruption during consumption") {
    val test =
      for {
        runtime   <- ZIO.runtime[Clock]
        requested <- Promise.make[Nothing, Unit]
        canceled  <- Promise.make[Nothing, Unit]
        publisher = new Publisher[Int] {
          override def subscribe(s: Subscriber[_ >: Int]): Unit =
            s.onSubscribe(
              new Subscription {
                override def request(n: Long): Unit = {
                  (1 to n.toInt).foreach(s.onNext(_))
                  runtime.unsafeRun(requested.succeed(()).unit)
                }
                override def cancel(): Unit = runtime.unsafeRun(canceled.succeed(()).unit)
              }
            )
        }
        fiber <- Sink.drain.toSubscriber()(s => UIO(publisher.subscribe(s))).fork
        _     <- requested.await.timeoutFail("timeout awaiting request.")(500.millis)
        _     <- fiber.interrupt
        _     <- canceled.await.timeoutFail("timeout awaiting cancellation.")(500.millis)
      } yield succeed
    unsafeRun(test)
  }

  def runTest(name: String, f: SubscriberBlackboxVerification[_] => Unit): Unit =
    test(name) {
      unsafeRun(
        Sink
          .collectAll[Int]
          .toSubscriber[Clock]() { subscriber =>
            ZManaged
              .make[Clock, Throwable, SubscriberBlackboxVerification[Int]] {
                val sbv =
                  new SubscriberBlackboxVerification[Int](env) {
                    override def createSubscriber(): Subscriber[Int] = subscriber
                    override def createElement(element: Int): Int    = element
                  }
                UIO(sbv.setUp()) *> UIO(sbv.startPublisherExecutorService()).as(sbv)
              } { sbv =>
                UIO(sbv.shutdownPublisherExecutorService())
              }
              .use { sbv =>
                Task(f(sbv)).timeout(env.defaultTimeoutMillis().millis).unit
              }
          }
          .catchAll {
            case _: TestException                                                    => UIO.unit
            case e: NullPointerException if (e.getMessage().contains("was null in")) => UIO.unit
            case e                                                                   => IO.fail(e)
          }
      )
    }

  runTest(
    "required_spec201_blackbox_mustSignalDemandViaSubscriptionRequest",
    _.required_spec201_blackbox_mustSignalDemandViaSubscriptionRequest
  )
  runTest(
    "required_spec203_blackbox_mustNotCallMethodsOnSubscriptionOrPublisherInOnComplete",
    _.required_spec203_blackbox_mustNotCallMethodsOnSubscriptionOrPublisherInOnComplete
  )
  runTest(
    "required_spec203_blackbox_mustNotCallMethodsOnSubscriptionOrPublisherInOnError",
    _.required_spec203_blackbox_mustNotCallMethodsOnSubscriptionOrPublisherInOnError
  )
  runTest(
    "required_spec205_blackbox_mustCallSubscriptionCancelIfItAlreadyHasAnSubscriptionAndReceivesAnotherOnSubscribeSignal",
    _.required_spec205_blackbox_mustCallSubscriptionCancelIfItAlreadyHasAnSubscriptionAndReceivesAnotherOnSubscribeSignal
  )
  runTest(
    "required_spec209_blackbox_mustBePreparedToReceiveAnOnCompleteSignalWithPrecedingRequestCall",
    _.required_spec209_blackbox_mustBePreparedToReceiveAnOnCompleteSignalWithPrecedingRequestCall
  )
  runTest(
    "required_spec209_blackbox_mustBePreparedToReceiveAnOnCompleteSignalWithoutPrecedingRequestCall",
    _.required_spec209_blackbox_mustBePreparedToReceiveAnOnCompleteSignalWithoutPrecedingRequestCall
  )
  runTest(
    "required_spec210_blackbox_mustBePreparedToReceiveAnOnErrorSignalWithPrecedingRequestCall",
    _.required_spec210_blackbox_mustBePreparedToReceiveAnOnErrorSignalWithPrecedingRequestCall
  )
  runTest(
    "required_spec210_blackbox_mustBePreparedToReceiveAnOnErrorSignalWithoutPrecedingRequestCall",
    _.required_spec210_blackbox_mustBePreparedToReceiveAnOnErrorSignalWithoutPrecedingRequestCall
  )
  runTest(
    "required_spec213_blackbox_onError_mustThrowNullPointerExceptionWhenParametersAreNull",
    _.required_spec213_blackbox_onError_mustThrowNullPointerExceptionWhenParametersAreNull
  )
  runTest(
    "required_spec213_blackbox_onNext_mustThrowNullPointerExceptionWhenParametersAreNull",
    _.required_spec213_blackbox_onNext_mustThrowNullPointerExceptionWhenParametersAreNull
  )
  runTest(
    "required_spec213_blackbox_onSubscribe_mustThrowNullPointerExceptionWhenParametersAreNull",
    _.required_spec213_blackbox_onSubscribe_mustThrowNullPointerExceptionWhenParametersAreNull
  )
  ignore("untested_spec202_blackbox_shouldAsynchronouslyDispatch") {}
  ignore("untested_spec204_blackbox_mustConsiderTheSubscriptionAsCancelledInAfterRecievingOnCompleteOrOnError") {}
  ignore("untested_spec206_blackbox_mustCallSubscriptionCancelIfItIsNoLongerValid") {}
  ignore(
    "untested_spec207_blackbox_mustEnsureAllCallsOnItsSubscriptionTakePlaceFromTheSameThreadOrTakeCareOfSynchronization"
  ) {}
  ignore("untested_spec208_blackbox_mustBePreparedToReceiveOnNextSignalsAfterHavingCalledSubscriptionCancel") {}
  ignore("untested_spec211_blackbox_mustMakeSureThatAllCallsOnItsMethodsHappenBeforeTheProcessingOfTheRespectiveEvents") {}
  ignore("untested_spec212_blackbox_mustNotCallOnSubscribeMoreThanOnceBasedOnObjectEquality") {}
  ignore("untested_spec213_blackbox_failingOnSignalInvocation") {}
  ignore("untested_spec301_blackbox_mustNotBeCalledOutsideSubscriberContext") {}
  ignore("untested_spec308_blackbox_requestMustRegisterGivenNumberElementsToBeProduced") {}
  ignore("untested_spec310_blackbox_requestMaySynchronouslyCallOnNextOnSubscriber") {}
  ignore("untested_spec311_blackbox_requestMaySynchronouslyCallOnCompleteOrOnError") {}
  ignore("untested_spec314_blackbox_cancelMayCauseThePublisherToShutdownIfNoOtherSubscriptionExists") {}
  ignore("untested_spec315_blackbox_cancelMustNotThrowExceptionAndMustSignalOnError") {}
  ignore("untested_spec316_blackbox_requestMustNotThrowExceptionAndMustOnErrorTheSubscriber") {}

}
