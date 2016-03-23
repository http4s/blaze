package org.http4s.blaze.http.http20

private final class StreamIdManager {
  import StreamIdManager._

  private var _lastClientId: Int = 0
  private var _nextServerId: Int = 2

  /** Determine if the client ID is valid based on the stream history */
  def validateClientId(id: Int): Boolean = {
    if (isClientId(id) && id > _lastClientId) {
      _lastClientId = id
      true
    }
    else false
  }

  /** Get the identifier of the last received client stream */
  def lastClientId(): Int = _lastClientId

  /** Get the next valid server id */
  def nextServerId(): Int = {
    val id = _nextServerId
    _nextServerId += 2
    id
  }
}

object StreamIdManager {
  def isClientId(id: Int): Boolean = {
    id > 0 && id % 2 == 1 // client ids are odd numbers
  }

  def isServerId(id: Int): Boolean = {
    id > 0 && id % 2 == 0 // server ids are even numbers
  }
}