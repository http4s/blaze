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

package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.Http2Settings._
import org.log4s.getLogger
import scala.collection.mutable

/** A bundle of HTTP2 settings
  *
  * These represent the HTTP2 settings for either the client or server.
  *
  * @see
  *   https://tools.ietf.org/html/rfc7540#section-6.5.2, where the doc strings were obtained.
  */
sealed abstract class Http2Settings {

  /** Allows the sender to inform the remote endpoint of the maximum size of the header compression
    * table used to decode header blocks, in octets. The encoder can select any size equal to or
    * less than this value by using signaling specific to the header compression format inside a
    * header block.
    */
  def headerTableSize: Int

  /** Indicates the sender's initial window size (in octets) for stream-level flow control.
    */
  def initialWindowSize: Int

  /** This setting can be used to disable server push (Section 8.2). */
  def pushEnabled: Boolean

  /** Indicates the maximum number of concurrent streams that the sender will allow. This limit is
    * directional: it applies to the number of streams that the sender permits the receiver to
    * create. Initially, there is no limit to this value. It is recommended that this value be no
    * smaller than 100, so as to not unnecessarily limit parallelism.
    *
    * A value of 0 for SETTINGS_MAX_CONCURRENT_STREAMS SHOULD NOT be treated as special by
    * endpoints. A zero value does prevent the creation of new streams; however, this can also
    * happen for any limit that is exhausted with active streams. Servers SHOULD only set a zero
    * value for short durations; if a server does not wish to accept requests, closing the
    * connection is more appropriate.
    */
  def maxConcurrentStreams: Int

  /** Indicates the size of the largest frame payload that the sender is willing to receive, in
    * octets.
    */
  def maxFrameSize: Int

  /** This advisory setting informs a peer of the maximum size of header list that the sender is
    * prepared to accept, in octets. The value is based on the uncompressed size of header fields,
    * including the length of the name and value in octets plus an overhead of 32 octets for each
    * header field.
    */
  def maxHeaderListSize: Int

  final def toSeq: Seq[Setting] =
    Seq(
      HEADER_TABLE_SIZE(headerTableSize),
      ENABLE_PUSH(if (pushEnabled) 1 else 0),
      MAX_CONCURRENT_STREAMS(maxConcurrentStreams),
      INITIAL_WINDOW_SIZE(initialWindowSize),
      MAX_FRAME_SIZE(maxFrameSize),
      MAX_HEADER_LIST_SIZE(maxHeaderListSize)
    )

  override def toString: String = s"Http2Settings($toSeq)"
}

/** Immutable representation of [[Http2Settings]] for configuring clients and servers */
case class ImmutableHttp2Settings(
    headerTableSize: Int,
    initialWindowSize: Int,
    pushEnabled: Boolean,
    maxConcurrentStreams: Int,
    maxFrameSize: Int,
    maxHeaderListSize: Int)
    extends Http2Settings

object Http2Settings {
  type SettingValue = Int

  object DefaultSettings {
    // https://tools.ietf.org/html/rfc7540#section-6.5.2
    val HEADER_TABLE_SIZE = 4096 // octets
    val ENABLE_PUSH = true // 1
    val MAX_CONCURRENT_STREAMS = Integer.MAX_VALUE // (infinite)
    val INITIAL_WINDOW_SIZE = 65535 // octets (2^16)
    val MAX_FRAME_SIZE = 16384 // octets (2^14)
    val MAX_HEADER_LIST_SIZE = Integer.MAX_VALUE // octets (infinite)
  }

  final case class Setting(code: Int, value: SettingValue) {

    /** Get the SettingKey associated with this setting */
    def key: SettingKey = settingKey(code)

    /** Get the human readable name of this setting */
    def name: String = key.name

    override def toString: String =
      s"$name(0x${Integer.toHexString(code)}, $value"
  }

  /** Create a new [[Http2Settings]] initialized with the protocol defaults */
  val default: ImmutableHttp2Settings = ImmutableHttp2Settings(
    headerTableSize = DefaultSettings.HEADER_TABLE_SIZE,
    initialWindowSize = DefaultSettings.INITIAL_WINDOW_SIZE,
    pushEnabled = DefaultSettings.ENABLE_PUSH, // initially enabled
    maxConcurrentStreams = DefaultSettings.MAX_CONCURRENT_STREAMS, // initially unbounded
    maxFrameSize = DefaultSettings.MAX_FRAME_SIZE,
    maxHeaderListSize = DefaultSettings.MAX_HEADER_LIST_SIZE
  )

  private def settingKey(id: Int): SettingKey =
    settingsMap.getOrElse(id, SettingKey(id, s"UNKNOWN_SETTING(0x${Integer.toHexString(id)})"))

  private val settingsMap = new mutable.HashMap[Int, SettingKey]()

  private def makeKey(code: Int, name: String): SettingKey = {
    val k = SettingKey(code, name)
    settingsMap += code -> k
    k
  }

  /** Helper for extracting invalid settings
    *
    * @see
    *   https://tools.ietf.org/html/rfc7540#section-6.5.2
    */
  object InvalidSetting {
    def unapply(setting: Setting): Option[Http2Exception] =
      setting match {
        case ENABLE_PUSH(v) if !isBoolSetting(v) =>
          Some(Http2Exception.PROTOCOL_ERROR.goaway(s"Invalid ENABLE_PUSH value: $v"))

        case MAX_FRAME_SIZE(size) if 16384 > size || size > 16777215 =>
          Some(Http2Exception.PROTOCOL_ERROR.goaway(s"Invalid MAX_FRAME_SIZE: $size"))

        case Setting(_, value) if value < 0 =>
          Some(
            Http2Exception.PROTOCOL_ERROR.goaway(
              s"Integer overflow for setting ${setting.name}: $value"))

        case _ => None
      }

    private def isBoolSetting(i: Int): Boolean =
      i match {
        case 0 | 1 => true
        case _ => false
      }
  }

  final case class SettingKey(code: Int, name: String) {
    override def toString: String = name

    /** Create a new `Setting` with the provided value */
    def apply(value: SettingValue): Setting = Setting(code, value)

    /** Extract the value from the key */
    def unapply(setting: Setting): Option[SettingValue] =
      if (setting.code == code) Some(setting.value)
      else None
  }

  val HEADER_TABLE_SIZE: SettingKey = makeKey(0x1, "SETTINGS_HEADER_TABLE_SIZE")
  val ENABLE_PUSH: SettingKey = makeKey(0x2, "SETTINGS_ENABLE_PUSH")
  val MAX_CONCURRENT_STREAMS: SettingKey =
    makeKey(0x3, "SETTINGS_MAX_CONCURRENT_STREAMS")
  val INITIAL_WINDOW_SIZE: SettingKey =
    makeKey(0x4, "SETTINGS_INITIAL_WINDOW_SIZE")
  val MAX_FRAME_SIZE: SettingKey = makeKey(0x5, "SETTINGS_MAX_FRAME_SIZE")
  val MAX_HEADER_LIST_SIZE: SettingKey =
    makeKey(0x6, "SETTINGS_MAX_HEADER_LIST_SIZE")
}

/** Internal mutable representation of the [[Http2Settings]] */
private[blaze] final class MutableHttp2Settings private (
    var headerTableSize: Int,
    var initialWindowSize: Int,
    var pushEnabled: Boolean,
    var maxConcurrentStreams: Int,
    var maxFrameSize: Int,
    var maxHeaderListSize: Int
) extends Http2Settings { // initially unbounded
  import MutableHttp2Settings._

  def updateSettings(newSettings: Seq[Setting]): Option[Http2Exception] = {
    import Http2Settings._

    val invalidSettingError = newSettings.collectFirst { case i @ InvalidSetting(ex) =>
      logger.info(ex)(s"Received invalid setting $i")
      ex
    }

    // If we didn't detect an invalid setting we can update ourselves
    if (invalidSettingError.isEmpty) newSettings.foreach {
      case HEADER_TABLE_SIZE(size) => headerTableSize = size.toInt
      case ENABLE_PUSH(v) => pushEnabled = v != 0
      case MAX_CONCURRENT_STREAMS(max) => maxConcurrentStreams = max.toInt
      case INITIAL_WINDOW_SIZE(size) => initialWindowSize = size.toInt
      case MAX_FRAME_SIZE(size) => maxFrameSize = size.toInt
      case MAX_HEADER_LIST_SIZE(size) => maxHeaderListSize = size.toInt
      case setting =>
        logger.info(s"Received setting $setting which we don't know what to do with. Ignoring.")
    }

    invalidSettingError
  }

  override def toString: String =
    s"MutableHttp2Settings(${toSeq.mkString(", ")})"
}

private object MutableHttp2Settings {
  private val logger = getLogger

  /** Construct a new [[MutableHttp2Settings]] from a [[Http2Settings]] instance */
  def apply(settings: Http2Settings): MutableHttp2Settings =
    new MutableHttp2Settings(
      headerTableSize = settings.headerTableSize,
      initialWindowSize = settings.initialWindowSize,
      pushEnabled = settings.pushEnabled,
      maxConcurrentStreams = settings.maxConcurrentStreams,
      maxFrameSize = settings.maxFrameSize,
      maxHeaderListSize = settings.maxHeaderListSize
    )

  /** Create a new [[MutableHttp2Settings]] using the HTTP2 defaults */
  def default(): MutableHttp2Settings =
    MutableHttp2Settings(Http2Settings.default)
}
