package org.http4s.blaze.http.http20

import org.http4s.blaze.http.http20.Settings.DefaultSettings

import scala.collection.mutable

object Settings {

  type SettingValue = Long

  final case class Setting(key: SettingKey, value: SettingValue)
  object Setting {
    def apply(key: Int, value: SettingValue): Setting = Setting(settingKey(key), value)
  }

  def settingKey(id: Int): SettingKey =
    settingsMap.getOrElse(id, SettingKey(id, s"UNKNOWN_SETTING(${Integer.toHexString(id)})"))

  private val settingsMap = new mutable.HashMap[Int, SettingKey]()

  private def mkKey(id: Int, name: String): SettingKey = {
    val k = SettingKey(id, name)
    settingsMap += ((id, k))
    k
  }

  final case class SettingKey(id: Int, name: String) {
    override def toString = name
    def toShort: Short = id.toShort
  }

  val HEADER_TABLE_SIZE      = mkKey(0x1, "SETTINGS_HEADER_TABLE_SIZE")
  val ENABLE_PUSH            = mkKey(0x2, "SETTINGS_ENABLE_PUSH")
  val MAX_CONCURRENT_STREAMS = mkKey(0x3, "SETTINGS_MAX_CONCURRENT_STREAMS")
  val INITIAL_WINDOW_SIZE    = mkKey(0x4, "SETTINGS_INITIAL_WINDOW_SIZE")
  val MAX_FRAME_SIZE         = mkKey(0x5, "SETTINGS_MAX_FRAME_SIZE")
  val MAX_HEADER_LIST_SIZE   = mkKey(0x6, "SETTINGS_MAX_HEADER_LIST_SIZE")

  object DefaultSettings {
    def HEADER_TABLE_SIZE = 4096                                  // Section 6.5.2
    def ENABLE_PUSH = true // 1                                   // Section 6.5.2
    def MAX_CONCURRENT_STREAMS = Integer.MAX_VALUE // (infinite)  // Section 6.5.2
    def INITIAL_WINDOW_SIZE = 65535                               // Section 6.5.2   2^16
    def MAX_FRAME_SIZE = 16384                                    // Section 6.5.2   2^14
    def MAX_HEADER_LIST_SIZE = Integer.MAX_VALUE //(infinite)     // Section 6.5.2
  }
}

final class Settings(
  var inboundWindow: Int = DefaultSettings.INITIAL_WINDOW_SIZE,
  var outbound_initial_window_size: Int = DefaultSettings.INITIAL_WINDOW_SIZE,
  var push_enable: Boolean = DefaultSettings.ENABLE_PUSH,                       // initially enabled
  var max_inbound_streams: Int = DefaultSettings.MAX_CONCURRENT_STREAMS,        // initially unbounded
  var max_outbound_streams: Int = DefaultSettings.MAX_CONCURRENT_STREAMS,       // initially unbounded
  var max_frame_size: Int = DefaultSettings.MAX_FRAME_SIZE,
  var max_header_size: Int = DefaultSettings.MAX_HEADER_LIST_SIZE               // initially unbounded
) {
  var receivedGoAway = false
}