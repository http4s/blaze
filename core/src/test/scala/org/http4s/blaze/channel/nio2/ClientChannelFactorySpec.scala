package org.http4s.blaze.channel.nio2

import java.net.{InetSocketAddress, SocketTimeoutException}

import org.http4s.blaze.test.FastTickWheelExecutor
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.DurationDouble

class ClientChannelFactorySpec extends Specification {

  "ClientChannelFactory" should {
    "time out" in new FastTickWheelExecutor {
      val factory =
        new ClientChannelFactory(connectTimeout = 1.millisecond, scheduler = scheduler)
      val address = new InetSocketAddress("192.0.2.0", 1) // rfc5737 TEST-NET-1

      Await.result(factory.connect(address), 500.milliseconds) should throwA[SocketTimeoutException]
    }
  }
}
