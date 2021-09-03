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

package org.http4s.blaze.channel.nio1

import java.nio.ByteBuffer

/** Type that can be registered with a [[SelectorLoop]]
  *
  * When registered with a `SelectorLoop` it will be notified when it has events ready.
  */
private trait Selectable {

  /** Called by the `SelectorLoop` when events are ready
    *
    * @param scratch
    *   a `ByteBuffer` that can be used for scratch area. This buffer is strictly borrowed for the
    *   life of the method call and will be passed to other `Selectable` instances.
    */
  def opsReady(scratch: ByteBuffer): Unit

  /** Close this `Selectable` and release associated resources. */
  def close(cause: Option[Throwable]): Unit
}
