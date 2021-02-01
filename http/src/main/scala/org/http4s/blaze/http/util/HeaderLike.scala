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

package org.http4s.blaze.http.util

/** Operations that are useful for packing and unpacking header representations
  */
trait HeaderLike[T] {
  def make(key: String, value: String): T
  def getKey(header: T): String
  def getValue(header: T): String
}

object HeaderLike {
  def apply[T](implicit hl: HeaderLike[T]): HeaderLike[T] = hl

  implicit val tupleHeaderLike: HeaderLike[(String, String)] =
    new HeaderLike[(String, String)] {
      override def make(key: String, value: String): (String, String) =
        ((key, value))
      override def getKey(header: (String, String)): String = header._1
      override def getValue(header: (String, String)): String = header._2
    }
}
