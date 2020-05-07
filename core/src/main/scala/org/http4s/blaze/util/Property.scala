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
