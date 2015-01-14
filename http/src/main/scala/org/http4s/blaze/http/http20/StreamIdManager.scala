package org.http4s.blaze.http.http20

class StreamIdManager {
  private var _lastClientId: Int = 0
  private var _nextServerId: Int = 2

  /** Determine if the client ID is valid based on the stream history */
  def validateClientId(id: Int): Boolean = {
    if (id > _lastClientId && id % 2 == 1) {
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