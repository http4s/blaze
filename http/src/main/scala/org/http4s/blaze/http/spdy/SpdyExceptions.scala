package org.http4s.blaze.http.spdy

abstract class SpdyException(msg: String) extends Exception(msg)

class ProtocolException(msg: String) extends SpdyException(msg)

