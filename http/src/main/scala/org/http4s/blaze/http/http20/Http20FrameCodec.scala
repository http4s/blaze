package org.http4s.blaze.http.http20

class Http20FrameCodec(val handler: FrameHandler)
   extends Http20FrameDecoder with Http20FrameEncoder
