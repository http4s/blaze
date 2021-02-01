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

package org.http4s.blaze.http

import scala.concurrent.Future

/** Provider of HTTP sessions
  *
  * Implementations of this interface are responsible for providing a HTTP session
  * on demand, ready to immediately perform a client request dispatch.
  *
  * @note It is required that the methods on implementations of this interface be thread safe.
  */
trait ClientSessionManager {

  /** Acquire a session that is believe to be healthy.
    *
    * This may be a session that has already existed or it may be a new session.
    */
  def acquireSession(request: HttpRequest): Future[HttpClientSession]

  /** Return the session to the pool.
    *
    * Depending on the state of the session and the nature of the pool, this may
    * either cache the session for future use or close it.
    */
  def returnSession(session: HttpClientSession): Unit

  /** Close the `SessionManager` and free any resources
    *
    * The returned `Future` will resolve once complete.
    */
  def close(): Future[Unit]
}
