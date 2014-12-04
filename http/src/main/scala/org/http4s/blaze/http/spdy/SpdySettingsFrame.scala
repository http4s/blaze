package org.http4s.blaze.http.spdy

import java.nio.{BufferOverflowException, ByteBuffer}

import org.http4s.blaze.util.BufferTools


object SettingFlag {

  val FLAG_SETTINGS_PERSIST_VALUE = 0x1
  val FLAG_SETTINGS_PERSISTED     = 0x2
}

object SettingID extends Enumeration {
  type SettingID = Value

  val SETTINGS_UPLOAD_BANDWIDTH               = Value(1)
  val SETTINGS_DOWNLOAD_BANDWIDTH             = Value(2)
  val SETTINGS_ROUND_TRIP_TIME                = Value(3)
  val SETTINGS_MAX_CONCURRENT_STREAMS         = Value(4)
  val SETTINGS_CURRENT_CWND                   = Value(5)
  val SETTINGS_DOWNLOAD_RETRANS_RATE          = Value(6)
  val SETTINGS_INITIAL_WINDOW_SIZE            = Value(7)
  val SETTINGS_CLIENT_CERTIFICATE_VECTOR_SIZE = Value(8)
}

import SettingID.SettingID

final case class Setting(flags: Int, id: SettingID, value: Array[Byte]) {

  if (value.length != 4) sys.error(s"Invalid length of value: ${value.length}")

  def intValue: Int = wrap.getInt

  def persistValue: Boolean = (flags & SettingFlag.FLAG_SETTINGS_PERSIST_VALUE) != 0

  def settingPersisted: Boolean = (flags & SettingFlag.FLAG_SETTINGS_PERSISTED) != 0

  def encode(buff: ByteBuffer) {
    if (buff.remaining() < 8) throw new BufferOverflowException

    buff.put((flags & 0xff).toByte)

    buff.put((id.id >>> 16 & 0xff).toByte)
        .put((id.id >>> 8  & 0xff).toByte)
        .put((id.id >>> 0  & 0xff).toByte)

    buff.put(value)
  }

  override def equals(obj: scala.Any): Boolean = obj match {
    case s: Setting =>
      if (this.flags        != s.flags ||
             this.id        != s.id    ||
          this.value.length != s.value.length)  return false

      var i = 0
      while (i < value.length) {
        if (this.value(i) != s.value(i)) return false
        i += 1
      }
      // If we made it the whole way here, everything matches
      return true

    case _ => false

  }


  override def toString: String = {
    val sb = new StringBuilder
    sb.append("(")
    if (persistValue) sb.append("Persist, ")
    if (settingPersisted) sb.append("Persisted, ")
    sb.append(id)
      .append(", ")
      .append(intValue)
      .append(")")

    sb.result()
  }

  private def wrap = ByteBuffer.wrap(value)
}

object Setting {
  def apply(flags: Int, id: SettingID, value: Int): Setting = {
    apply(flags, id, mkBuffer.putInt(value).array())
  }

  private def mkBuffer = BufferTools.allocate(4)
}

