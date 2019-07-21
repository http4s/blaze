package org.http4s.blaze.channel.nio2

import java.net.{InetSocketAddress, SocketTimeoutException}

import org.http4s.blaze.util.TickWheelExecutor
import org.specs2.mutable.{After, Specification}

import scala.concurrent.Await
import scala.concurrent.duration.DurationDouble

class ClientChannelFactorySpec extends Specification {

  "ClientChannelFactory" should {
    "time out" in new fastTickWheelExecutor {
      val factory =
        new ClientChannelFactory(connectingTimeout = Some(1.millisecond), scheduler = scheduler)
      val address = new InetSocketAddress("1.1.1.1", 1)

      Await.result(factory.connect(address), 500.milliseconds) should throwA[SocketTimeoutException]
    }
  }
}

trait fastTickWheelExecutor extends After {
  // The default TickWheelExecutor has 200ms ticks. It should be acceptable for most real world use cases.
  // If one needs very short timeouts (like we do in tests), providing a custom TickWheelExecutor is a solution.
  val scheduler: TickWheelExecutor = new TickWheelExecutor(tick = 10.millis)
  def after: Unit = {
    scheduler.shutdown()
    ()
  }
}
