package http_parser

/**
 * @author Bryce Anderson
 *         Created on 12/31/13
 */

trait ParserState

object ParserState {
  case object Idle extends ParserState
  case object NeedsInput extends ParserState
  case class ParseError(error: String) extends ParserState
}


abstract class ParserTools(protected var limit: Int) {

  import ParserTools._
  import ParserState._

  protected var _parserState: ParserState = Idle

  final def resetLimit(i: Int) { limit = i }

  // Just a bootstrap. Will replace with own Array[Char] and limits later
  final private val buffer = new java.lang.StringBuilder(40)

  final def isEmpty(): Boolean = buffer.length() == 0

  final def addChar(c: Char): Unit = buffer.append(c)

  final def charAt(i: Int): Char = buffer.charAt(i)

  final def string(start: Int, end: Int): String = buffer.substring(start, end)

  @inline
  final def result(): String = buffer.toString

  @inline
  final def bufferLength(): Int = buffer.length()

  @inline
  final def rewind(n: Int): Unit = buffer.setLength(buffer.length() - n)

  @inline
  final def rewind(): Unit = rewind(1)

  @inline
  final def setState(state: ParserState): Unit = _parserState = state

  @inline
  final def getState(): ParserState = _parserState

  def hasNext: Boolean

  protected def nextChar: Char

  @inline
  final def clearBuffer() = buffer.setLength(0)

  @inline private def checkIdle() {
    val state = getState()
    if (state != Idle) sys.error(s"State not idle: $state")
  }

  @inline
  final protected def setIDLE: Boolean = {
    setState(Idle)
    true
  }

  @inline
  final protected def needsInput: Boolean = {
    setState(NeedsInput)
    false
  }

  @inline
  final protected def error(e: String): Boolean = {
    setState(ParseError(e))
    false
  }

  final protected def readUntil(char: Char, keep: Boolean): Boolean = {
    checkIdle()

    var c: Char = 0

    while(hasNext && limit >= 0) {
      c = nextChar
      limit -= 1
      if (c == char) {
        if (keep) buffer.append(c)
        return setIDLE
      }
      buffer.append(c)

    }

    if (limit < 0) error(s"Parser exceeded maxlength")
    else setIDLE
  }

  final protected def finishNewline(): Boolean = {
    if (hasNext) {
      if (limit < 0) return error("Maximum length exceeded")
      val c = nextChar
      if (c != '\n') return error("Line ended without LF")
      else true
    } else needsInput
  }

  // Skips chars until it gets to a new line. Returns true if the cursor is now on a new line
  protected def loadLine(requirecr: Boolean): Boolean = {
    checkIdle()

    var c: Char = 0
    var cr = false

    while(hasNext && limit >= 0) {
      c = nextChar
      limit -= 1
      if (c == '\r') cr = true
      else if (c == '\n') {
        if (requirecr && !cr) {    // Did we require a which wasn't there CR
          return error("EOL missing the CR character")
        }
        else return setIDLE
      }
      else buffer.append(c)
    }

    if (limit < 0) {
      error(s"Line length exceeded")
    } else needsInput                   // out of input
  }

  final protected def skipWhitespace(): Boolean = {
    while(hasNext && limit >= 0) {
      val c = nextChar
      limit -= 1
      if (!isWhitespace(c)) {
        buffer.append(c)
        return setIDLE
      }
    }
    if (limit < 0) error(s"Line length exceeded")
    else needsInput
  }

  /** Reads bytes until it hits whitespace into the buffer
    *
    * @return true if reached whitespace or EOL, false if not determined
    */
  protected def loadToken(): Boolean = {
    checkIdle()

    if (!hasNext) return needsInput

    var c = nextChar
    limit -=1

    while(!isWhitespace(c) && !isEOLChar(c) && limit >= 0) {
      buffer.append(c)

      if (!hasNext) return needsInput

      c = nextChar
      limit -= 1
    }

    if (limit < 0) error(s"Maximum length exceeded")
    setIDLE
  }
}

trait StringParserTools extends ParserTools {

  protected def input: String

  private var _stringPosition = 0
  private lazy val _stringBytes = input.getBytes
  def nextChar: Char = {
    val c = _stringBytes(_stringPosition)
    _stringPosition += 1
    c.toChar
  }

  def hasNext: Boolean = _stringPosition < _stringBytes.length
}

object ParserTools {
  @inline
  final def isNum(c: Char): Boolean = c <= '9' && c >= '0'

  @inline
  final def isEOLChar(char: Char) = char == '\r' || char == '\n'

  @inline
  final def isWhitespace(char: Char): Boolean = {
    char == ' ' || char == '\t'
  }
}


