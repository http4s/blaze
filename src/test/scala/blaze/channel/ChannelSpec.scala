package blaze.channel

import org.scalatest.{Matchers, WordSpec}

import java.net.InetSocketAddress
import blaze.examples.EchoServer


/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
class ChannelSpec extends WordSpec with Matchers {


  "Channels" should {

    "Bind the port and the close" in {
      val server = new EchoServer().prepare(new InetSocketAddress(8080))
      server.close()
    }
  }

}
