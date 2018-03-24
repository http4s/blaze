package org.http4s.blaze.http

import org.http4s.blaze.util.Property

/** Enable logging of sensitive information such as header values and request content */
private[blaze] object logsensitiveinfo extends Property(default = false)
