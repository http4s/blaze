package org.http4s.blaze.http.http2.server

import org.specs2.mutable.Specification

class ServerStageSpec extends Specification {
  "ServerStage" >> {

    "fails to start with a non-headers frame" >> {
      ko
    }

    "generates an empty body if the first frame has EOS" >> {
      ko
    }

    "generates a body" >> {
      ko
    }

    "The service can write the body" >> {
      ko
    }

    "finishing the action disconnects the stage" >> {
      ko
    }

    "failing the action disconnects with an error" >> {
      ko
    }

    "head requests get a noop writer" >> {
      ko
    }
  }

}
