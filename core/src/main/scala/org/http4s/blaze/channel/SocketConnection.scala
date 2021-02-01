/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.channel

import java.net.{InetAddress, InetSocketAddress, SocketAddress}

trait SocketConnection {

  /** Return the SocketAddress of the remote connection */
  def remote: SocketAddress

  /** Return the local SocketAddress associated with the connection */
  def local: SocketAddress

  /** Return of the connection is currently open */
  def isOpen: Boolean

  /** Close this Connection */
  def close(): Unit

  final def remoteInetAddress: Option[InetAddress] =
    remote match {
      case addr: InetSocketAddress => Option(addr.getAddress)
      case _ => None
    }

  final def localInetAddress: Option[InetAddress] =
    local match {
      case addr: InetSocketAddress => Option(addr.getAddress)
      case _ => None
    }
}
