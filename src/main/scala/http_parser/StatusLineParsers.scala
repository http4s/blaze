package http_parser

/**
 * @author Bryce Anderson
 *         Created on 12/31/13
 */

import scala.collection.mutable.ListBuffer
import scala.annotation.tailrec

trait RequestLineParser { self: ParserTools =>

  import ParserTools._
  import ParserState._


  ///////////////// Header parser ///////////////////////////////////////////
  private val _headers = new ListBuffer[(String, String)]

  private var _headerKey: String = null

  def getHeaders: List[(String, String)] = _headers.result()

//  @tailrec
//  final private def parseKey(): Boolean = {
//
//    if (limit < 0) error("Maximum length exceeded")
//
//    else if (hasNext) {
//      val c = nextChar
//      limit -= 1
//      if (c == ':') {
//        _headerKey = result()
//        clearBuffer()
//        true
//      } else if (isEOLChar(c)) {
//        finishNewline()
//      } else {
//        addChar(c)
//        parseKey()
//      }
//    }
//
//    else needsInput
//  }

  final def parseHeaders(): Boolean = {
    while(true) {
      if (!loadLine(true) ) return false

      val sz = bufferLength()

      if (sz == 0) return true // We are done with headers

      var i = 0
      var valuestart = -1
      var continue = true
      while (i < sz && continue) {
        val c = charAt(i)
        if (c == ':')  continue = false
        i += 1
      }

      if (i == sz) _headers += ((result(), ""))     // valueless header
      else {
        val keyEnd = i - 1
        // Skip whitespace
        while (i < sz && valuestart < 0) {
          val c = charAt(i)
          if (!isWhitespace(c))  valuestart = i
          else i += 1
        }

        _headers += ((string(0, keyEnd), string(valuestart, sz)))
      }

      clearBuffer()
    }

    return error("Error parsing")
  }

  /////////////////////// StatusLine parser /////////////////////////////

  // States: method: 0, url: 1, scheme: 2, done: > 2
  private var state = 0

  private var method = ""
  private var url = ""
  private var scheme = ""
  private var major = -1
  private var minor = -1

  def handleLine(method: String, url: String, scheme: String, major: Int, minor: Int): Boolean

  final def parseRequestLine(): Boolean = {
    if (state == 0) {
      if (!loadToken()) return false
      method = result()
      clearBuffer()
      state = 1
      parseRequestLine()
    } else if (state == 1) {
      if (!loadToken()) return false
      url = result()
      clearBuffer()
      state = 2
      parseRequestLine()
    } else if (state == 2) {
      if (!loadLine(true)) return false
      if (httpVersion()) {      // decode http version and scheme
        clearBuffer()
        handleLine(method, url, scheme, major, minor)
      }
      else {
        setState(ParseError(s"Invalid scheme: $result"))
        false
      }

    } else {        // Invalid state. Shouldn't get here
      setState(ParserState.ParseError("Invalid state"))
      false
    }
  }

  private def httpVersion(): Boolean = {
    if (charAt(0) != 'H' || charAt(1) != 'T' || charAt(2) != 'T' || charAt(3) != 'P') return false

    var i = 4
    scheme = "http"

    if (charAt(i) == 'S') {
      scheme = "https"
      i += 1
    }

    if (charAt(i) != '/') return false
    
    val major = charAt(i + 1)
    if (!isNum(major)) return false
    this.major =  major - '0'
    
    if (charAt(i + 2) != '.') return false

    val minor = charAt(i + 3)
    if (!isNum(minor)) return false
    this.minor =  minor - '0'
    return true
  }
}

trait HeaderParser { self: ParserTools =>

  import ParserTools._


}
