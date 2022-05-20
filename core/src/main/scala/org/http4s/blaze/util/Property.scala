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

package org.http4s.blaze.util

/** Helper for defining behavior specific to properties */
private[blaze] abstract class Property(default: Boolean) {
  // This may not be the most robust way to fix up the names of objects.
  final val name = getClass.getName.replace("$", "")

  /** Get the value of the property if it is set */
  final def get: Option[Boolean] =
    System.getProperty(name) match {
      case null => None
      case value => Some(value == "true")
    }

  /** Get the value of this property, defaulting to `false` if it is not set. */
  final def apply(): Boolean =
    System.getProperty(name) match {
      case null => default
      case value => value == "true"
    }

  /** Set the value of this property */
  final def set(value: Boolean): Unit = {
    System.setProperty(name, value.toString)
    ()
  }
}
