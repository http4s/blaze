package org.http4s.blaze.http.http20

object bits {

  def HeaderSize = 9
  def clientTLSHandshakeString = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"

  object Masks {
    val STREAMID = 0x7fffffff
    val LENGTH   =   0xffffff
    val int31    = 0x7fffffff
    val exclsive = ~int31
  }

  object FrameTypes {
    val DATA          = 0x0.toByte
    val HEADERS       = 0x1.toByte
    val PRIORITY      = 0x2.toByte
    val RST_STREAM    = 0x3.toByte
    val SETTINGS      = 0x4.toByte
    val PUSH_PROMISE  = 0x5.toByte
    val PING          = 0x6.toByte
    val GOAWAY        = 0x7.toByte
    val WINDOW_UPDATE = 0x8.toByte
    val CONTINUATION  = 0x9.toByte
  }

  //////////////////////////////////////////////////

  object Flags {
    val END_STREAM = 0x1.toByte
    def END_STREAM(flags: Byte): Boolean  = checkFlag(flags, END_STREAM)   // Data, Header

    val PADDED = 0x8.toByte
    def PADDED(flags: Byte): Boolean      = checkFlag(flags, PADDED)       // Data, Header

    val END_HEADERS = 0x4.toByte
    def END_HEADERS(flags: Byte): Boolean = checkFlag(flags, END_HEADERS)  // Header, push_promise

    val PRIORITY = 0x20.toByte
    def PRIORITY(flags: Byte): Boolean    = checkFlag(flags, PRIORITY)     // Header

    val ACK = 0x1.toByte
    def ACK(flags: Byte): Boolean         = checkFlag(flags, ACK)          // ping

    def DepID(id: Int): Int               = id & Masks.int31
    def DepExclusive(id: Int): Boolean    = (Masks.exclsive & id) != 0

    @inline
    private def checkFlag(flags: Byte, flag: Byte) = (flags & flag) != 0
  }
}
