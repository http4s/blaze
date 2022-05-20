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
import org.http4s.blaze.http.parser.BaseExceptions.*;

public abstract class Http1ClientParser extends BodyAndHeaderParser {

  public Http1ClientParser(
      int maxResponseLineSize,
      int maxHeaderLength,
      int initialBufferSize,
      int maxChunkSize,
      boolean isLenient) {
    super(initialBufferSize, maxHeaderLength, maxChunkSize, isLenient);
    this.maxResponseLineSize = maxResponseLineSize;

    _internalReset();
  }

  public Http1ClientParser(int initialBufferSize, boolean isLenient) {
    this(2048, 40 * 1024, initialBufferSize, Integer.MAX_VALUE, isLenient);
  }

  public Http1ClientParser(boolean isLenient) {
    this(10 * 1024, isLenient);
  }

  public Http1ClientParser() {
    this(false);
  }

  // Status-Line = HTTP-Version SP Status-Code SP Reason-Phrase CRLF
  private enum RequestLineState {
    START,
    VERSION,
    SPACE1,
    STATUS_CODE,
    SPACE2,
    REASON,
    END,
  }

  private final int maxResponseLineSize;

  private RequestLineState _requestLineState = RequestLineState.START;
  private String _lineScheme = null;
  private int _majorVersion = 0;
  private int _minorVersion = 0;
  private int _statusCode = 0;
  private boolean isHeadRequest = false;

  public final boolean isStartState() {
    return _requestLineState == RequestLineState.START;
  }

  protected abstract void submitResponseLine(
      int code, String reason, String scheme, int majorversion, int minorversion);

  public final boolean responseLineComplete() {
    return _requestLineState == RequestLineState.END;
  }

  @Override
  public void shutdownParser() {
    super.shutdownParser(); // To change body of overridden methods use File | Settings | File
    // Templates.
    _requestLineState = RequestLineState.END;
  }

  @Override
  public void reset() {
    super.reset();
    _internalReset();
  }

  private void _internalReset() {
    _requestLineState = RequestLineState.START;
    _lineScheme = null;
    _majorVersion = 0;
    _minorVersion = 0;
    _statusCode = 0;
    isHeadRequest = false;
  }

  public final void isHeadRequest(boolean isHeadRequest) {
    this.isHeadRequest = isHeadRequest;
  }

  @Override
  public boolean mustNotHaveBody() {
    return isHeadRequest || _statusCode < 200 || _statusCode == 204 || _statusCode == 304;
  }

  /** parses the request line. Returns true if completed successfully, false if needs input */
  @SuppressWarnings("fallthrough")
  protected final boolean parseResponseLine(ByteBuffer in)
      throws BaseExceptions.InvalidState, BaseExceptions.BadMessage {
    try {
      lineLoop:
      while (true) {
        char ch;
        switch (_requestLineState) {
          case START:
            _requestLineState = RequestLineState.VERSION;
            resetLimit(maxResponseLineSize);

          case VERSION:
            for (ch = next(in, false);
                ch != HttpTokens.SPACE && ch != HttpTokens.TAB;
                ch = next(in, false)) {
              if (ch == HttpTokens.EMPTY_BUFF) return false;
              putChar(ch);
            }

            _majorVersion = 1;
            _minorVersion = 1;

            if (arrayMatches(HTTP11Bytes) || arrayMatches(BodyAndHeaderParser.HTTPS11Bytes)) {
              _majorVersion = 1;
              _minorVersion = 1;
            } else if (arrayMatches(HTTP10Bytes) || arrayMatches(HTTPS10Bytes)) {
              _majorVersion = 1;
              _minorVersion = 0;
            } else {
              String reason = "Bad HTTP version: " + getString();
              clearBuffer();
              shutdownParser();
              throw new BadMessage(reason);
            }

            _lineScheme = getString(bufferPosition() - 4);
            clearBuffer();

            // We are through parsing the request line
            _requestLineState = RequestLineState.SPACE1;

          case SPACE1:
            // Eat whitespace
            for (ch = next(in, false);
                ch == HttpTokens.SPACE || ch == HttpTokens.TAB;
                ch = next(in, false)) ;

            if (ch == HttpTokens.EMPTY_BUFF) return false;

            if (!HttpTokens.isDigit(ch)) {
              shutdownParser();
              throw new BadMessage("Received invalid token when needed code: '" + ch + "'");
            }
            _statusCode = 10 * _statusCode + (ch - HttpTokens.ZERO);
            _requestLineState = RequestLineState.STATUS_CODE;

          case STATUS_CODE:
            for (ch = next(in, false); HttpTokens.isDigit(ch); ch = next(in, false)) {
              _statusCode = 10 * _statusCode + (ch - HttpTokens.ZERO);
            }

            if (ch == HttpTokens.EMPTY_BUFF) return false; // Need more data

            if (_statusCode < 100 || _statusCode >= 600) {
              shutdownParser();
              throw new BadMessage(
                  "Invalid status code '" + _statusCode + "'. Must be between 100 and 599");
            }

            if (ch == HttpTokens.LF) {
              endResponseLineParsing("");
              return true;
            }

            if (!HttpTokens.isWhiteSpace(ch)) {
              shutdownParser();
              throw new BadMessage("Invalid request: Expected SPACE but found '" + ch + "'");
            }

            _requestLineState = RequestLineState.SPACE2;

          case SPACE2:
            // Eat whitespace
            for (ch = next(in, false);
                ch == HttpTokens.SPACE || ch == HttpTokens.TAB;
                ch = next(in, false)) ;

            if (ch == HttpTokens.EMPTY_BUFF) return false;

            if (ch == HttpTokens.LF) {
              endResponseLineParsing("");
              return true;
            }

            putChar(ch);
            _requestLineState = RequestLineState.REASON;

          case REASON:
            for (ch = next(in, false); ch != HttpTokens.LF; ch = next(in, false)) {
              if (ch == HttpTokens.EMPTY_BUFF) return false;
              putChar(ch);
            }

            String reason = getTrimmedString();
            endResponseLineParsing(reason);
            return true;

          default:
            throw new BaseExceptions.InvalidState(
                "Attempted to parse Response line when already complete."
                    + "RequestLineState: '"
                    + _requestLineState
                    + "'");
        } // switch
      } // while loop
    } catch (BaseExceptions.BadMessage ex) {
      shutdownParser();
      throw ex;
    }
  }

  private void endResponseLineParsing(String reason) {
    clearBuffer();

    // We are through parsing the request line
    _requestLineState = RequestLineState.END;
    submitResponseLine(_statusCode, reason, _lineScheme, _majorVersion, _minorVersion);
  }
}
