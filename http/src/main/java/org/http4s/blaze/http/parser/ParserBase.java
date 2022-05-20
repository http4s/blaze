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

package org.http4s.blaze.http.parser;

import java.nio.ByteBuffer;
import org.http4s.blaze.http.parser.BaseExceptions.BadMessage;
import org.http4s.blaze.http.parser.BaseExceptions.BadCharacter;

public abstract class ParserBase {

  ParserBase(int initialBufferSize, boolean isLenient) {
    _internalBuffer = new char[initialBufferSize];
    _isLenient = isLenient;
    clearBuffer();
  }

  private final boolean _isLenient;

  private int _bufferPosition = 0;
  private char[] _internalBuffer;

  // Signals if the last char was a '\r' and if the next one needs to be a '\n'
  private boolean _cr;

  // For signalling overflow of the String buffer
  private int _segmentByteLimit;
  private int _segmentBytePosition;

  /** for shutting down the parser and its state */
  public void shutdownParser() {
    clearBuffer();
  }

  void reset() {
    clearBuffer();
  }

  /** Store the char in the internal buffer */
  protected final void putChar(char c) {
    final int clen = _internalBuffer.length;
    if (clen == _bufferPosition) {
      final char[] next = new char[2 * clen + 1];

      System.arraycopy(_internalBuffer, 0, next, 0, _bufferPosition);
      _internalBuffer = next;
    }

    _internalBuffer[_bufferPosition++] = c;
  }

  protected final int bufferPosition() {
    return _bufferPosition;
  }

  protected final boolean isLenient() {
    return _isLenient;
  }

  protected final void clearBuffer() {
    _bufferPosition = 0;
  }

  protected final String getString() {
    return getString(0, _bufferPosition);
  }

  protected final String getString(int end) {
    return getString(0, end);
  }

  protected final String getString(int start, int end) {
    if (end > _bufferPosition) {
      throw new IndexOutOfBoundsException("Requested: " + end + ", max: " + _bufferPosition);
    }

    String str = new String(_internalBuffer, start, end);
    return str;
  }

  /** Returns the string in the buffer minus an leading or trailing whitespace or quotes */
  protected final String getTrimmedString() throws BadMessage {
    if (_bufferPosition == 0) return "";

    int start = 0;
    boolean quoted = false;
    // Look for start
    while (start < _bufferPosition) {
      final char ch = _internalBuffer[start];
      if (ch == '"') {
        quoted = true;
        break;
      } else if (ch != HttpTokens.SPACE && ch != HttpTokens.TAB) {
        break;
      }
      start++;
    }

    int end = _bufferPosition; // Position is of next write

    // Look for end
    while (end > start) {
      final char ch = _internalBuffer[end - 1];

      if (quoted) {
        if (ch == '"') break;
        else if (ch != HttpTokens.SPACE && ch != HttpTokens.TAB) {
          throw new BadMessage("String might not quoted correctly: '" + getString() + "'");
        }
      } else if (ch != HttpTokens.SPACE && ch != HttpTokens.TAB) break;
      end--;
    }

    String str = new String(_internalBuffer, start, end - start);

    return str;
  }

  protected final boolean arrayMatches(final char[] chars) {
    if (chars.length != _bufferPosition) return false;

    for (int i = 0; i < _bufferPosition; i++) {
      if (chars[i] != _internalBuffer[i]) return false;
    }

    return true;
  }

  /* ------------------------------------------------------------------- */

  protected final void resetLimit(int limit) {
    _segmentByteLimit = limit;
    _segmentBytePosition = 0;
  }

  // Removes CRs but returns LFs
  protected final char next(final ByteBuffer buffer, boolean allow8859)
      throws BaseExceptions.BadMessage {

    if (!buffer.hasRemaining()) return HttpTokens.EMPTY_BUFF;

    if (_segmentByteLimit <= _segmentBytePosition) {
      shutdownParser();
      throw new BaseExceptions.BadMessage("Request length limit exceeded: " + _segmentByteLimit);
    }

    final byte b = buffer.get();
    _segmentBytePosition++;

    // If we ended on a CR, make sure we are
    if (_cr) {
      if (b != HttpTokens.LF) {
        throw new BadCharacter("Invalid sequence: LF didn't follow CR: " + b);
      }
      _cr = false;
      return (char) b; // must be LF
    }

    // Make sure its a valid character
    if (b < HttpTokens.SPACE) {
      if (b == HttpTokens.CR) { // Set the flag to check for _cr and just run again
        _cr = true;
        return next(buffer, allow8859);
      } else if (b == HttpTokens.TAB || allow8859 && b < 0) {
        return (char) (b & 0xff);
      } else if (b == HttpTokens.LF) {
        return (char) b; // A backend should accept a bare linefeed.
        // http://tools.ietf.org/html/rfc2616#section-19.3
      } else if (isLenient()) {
        return HttpTokens.REPLACEMENT;
      } else {
        shutdownParser();
        throw new BadCharacter(
            "Invalid char: '" + (char) (b & 0xff) + "', 0x" + Integer.toHexString(b));
      }
    }

    // valid ascii char
    return (char) b;
  }
}
