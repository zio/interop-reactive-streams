package zio.interop.reactiveStreams

import org.reactivestreams.Publisher
import org.reactivestreams.tck.{ PublisherVerification, TestEnvironment }
import org.scalatestplus.testng.TestNGSuiteLike
import zio.DefaultRuntime
import zio.stream.Stream
import zio.internal.PlatformLive

class StreamToPublisherTest(env: TestEnvironment, publisherShutdownTimeout: Long)
    extends PublisherVerification[Int](env, publisherShutdownTimeout)
    with TestNGSuiteLike
    with DefaultRuntime {

  override val Platform = PlatformLive.Default.withReportFailure(_ => ())

  def this() {
    this(new TestEnvironment(2000, 500), 2000)
  }

  def createPublisher(elements: Long): Publisher[Int] =
    unsafeRun(
      Stream
        .unfold(elements)(n => if (n > 0) Some((1, n - 1)) else None)
        .toPublisher
    )

  override def createFailedPublisher(): Publisher[Int] =
    unsafeRun(
      Stream
        .fail(new RuntimeException("boom!"))
        .map(_.asInstanceOf[Int])
        .toPublisher
    )

}
