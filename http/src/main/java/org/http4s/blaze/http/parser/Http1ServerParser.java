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

public abstract class Http1ServerParser extends BodyAndHeaderParser {

  private enum LineState {
    START,
    METHOD,
    SPACE1,
    PATH,
    SPACE2,
    VERSION,
    END,
  }

  private final int maxRequestLineSize;
  private LineState lineState = LineState.START;
  private String method;
  private String path;

  /**
   * This is the method called by parser when the HTTP request line is parsed
   *
   * @param methodString The method as a string
   * @param path The raw bytes of the PATH. These are copied into a ByteBuffer that will not be
   *     changed until this parser is reset and reused.
   * @param scheme the scheme of the request, either .http' or 'https'. Note that this doesn't
   *     necessarily correlate with using SSL/TLS.
   * @param majorVersion major version
   * @param minorVersion minor version
   * @return true if handling parsing should return.
   */
  public abstract boolean submitRequestLine(
      String methodString, String path, String scheme, int majorVersion, int minorVersion);

  /* ------------------------------------------------------------------ */

  public final boolean requestLineComplete() {
    return lineState == LineState.END;
  }

  /** Whether the parser is in the initial state. */
  public final boolean isStart() {
    return lineState == LineState.START;
  }

  /* ------------------------------------------------------------------ */

  @Override
  public void reset() {
    super.reset();
    internalReset();
  }

  private void internalReset() {
    lineState = LineState.START;
  }

  /* ------------------------------------------------------------------ */

  @Override
  public void shutdownParser() {
    super.shutdownParser();
    lineState = LineState.END;
  }

  /* ------------------------------------------------------------------ */
  // the sole Constructor

  public Http1ServerParser(
      int maxReqLen, int maxHeaderLength, int initialBufferSize, int maxChunkSize) {
    super(initialBufferSize, maxHeaderLength, maxChunkSize, false);

    this.maxRequestLineSize = maxReqLen;
    internalReset();
  }

  public Http1ServerParser(int maxReqLen, int maxHeaderLength, int initialBufferSize) {
    this(maxReqLen, maxHeaderLength, initialBufferSize, Integer.MAX_VALUE);
  }

  public Http1ServerParser(int initialBufferSize) {
    this(2048, 40 * 1024, initialBufferSize);
  }

  public Http1ServerParser() {
    this(10 * 1024);
  }

  /* ------------------------------------------------------------------ */

  @Override
  public boolean mustNotHaveBody() {
    // A request must always indicate with either content-length or
    // transfer-encoding if it is going to have a body.
    return !(definedContentLength() || isChunked());
  }

  /* ------------------------------------------------------------------ */

  /** parses the request line. Returns true if completed successfully, false if needs input */
  @SuppressWarnings("fallthrough")
  protected final boolean parseRequestLine(ByteBuffer in) throws InvalidState, BadMessage {
    lineLoop:
    while (true) {
      char ch;
      switch (lineState) {
        case START:
          lineState = LineState.METHOD;
          resetLimit(maxRequestLineSize);

        case METHOD:
          for (ch = next(in, false);
              HttpTokens.A <= ch && ch <= HttpTokens.Z;
              ch = next(in, false)) {
            putChar(ch);
          }

          if (ch == HttpTokens.EMPTY_BUFF) return false;

          method = getString();
          clearBuffer();

          if (!HttpTokens.isWhiteSpace(ch)) {
            String badmethod = method + ch;
            shutdownParser();
            throw new BadMessage("Invalid request method: '" + badmethod + "'");
          }

          lineState = LineState.SPACE1;

        case SPACE1:
          // Eat whitespace
          for (ch = next(in, false);
              ch == HttpTokens.SPACE || ch == HttpTokens.TAB;
              ch = next(in, false)) ;

          if (ch == HttpTokens.EMPTY_BUFF) return false;

          putChar(ch);
          lineState = LineState.PATH;

        case PATH:
          for (ch = next(in, false);
              ch != HttpTokens.SPACE && ch != HttpTokens.TAB;
              ch = next(in, false)) {
            if (ch == HttpTokens.EMPTY_BUFF) return false;
            putChar(ch);
          }

          path = getString();
          clearBuffer();

          lineState = LineState.SPACE2;

        case SPACE2:
          // Eat whitespace
          for (ch = next(in, false);
              ch == HttpTokens.SPACE || ch == HttpTokens.TAB;
              ch = next(in, false)) ;

          if (ch == HttpTokens.EMPTY_BUFF) return false;

          if (ch != 'H') {
            shutdownParser();
            throw new BadMessage("Http version started with illegal character: " + ch);
          }

          putChar(ch);
          lineState = LineState.VERSION;

        case VERSION:
          for (ch = next(in, false); ch != HttpTokens.LF; ch = next(in, false)) {
            if (ch == HttpTokens.EMPTY_BUFF) return false;
            putChar(ch);
          }

          int majorVersion;
          int minorVersion;
          String scheme;

          if (arrayMatches(HTTP11Bytes)) {
            majorVersion = 1;
            minorVersion = 1;
            scheme = "http";
          } else if (arrayMatches(HTTPS11Bytes)) {
            majorVersion = 1;
            minorVersion = 1;
            scheme = "https";
          } else if (arrayMatches(HTTP10Bytes)) {
            majorVersion = 1;
            minorVersion = 0;
            scheme = "http";
          } else if (arrayMatches(HTTPS10Bytes)) {
            majorVersion = 1;
            minorVersion = 0;
            scheme = "https";
          } else {
            String reason = "Bad HTTP version: " + getString();
            clearBuffer();
            shutdownParser();
            throw new BadMessage(reason);
          }

          clearBuffer();

          // We are through parsing the request line
          lineState = LineState.END;
          return !submitRequestLine(method, path, scheme, majorVersion, minorVersion);

        default:
          throw new InvalidState(
              "Attempted to parse Request line when already "
                  + "complete. LineState: '"
                  + lineState
                  + "'");
      } // switch
    } // while loop
  }
}
