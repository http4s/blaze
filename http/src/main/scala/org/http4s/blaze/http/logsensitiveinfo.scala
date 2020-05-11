/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http

import org.http4s.blaze.util.Property

/** Enable logging of sensitive information such as header values and request content */
private[blaze] object logsensitiveinfo extends Property(default = false)
