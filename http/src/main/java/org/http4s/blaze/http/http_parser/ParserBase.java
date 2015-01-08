package org.http4s.blaze.http.http_parser;


import java.nio.ByteBuffer;
import org.http4s.blaze.http.http_parser.BaseExceptions.BadRequest;

public abstract class ParserBase {

    ParserBase(int initialBufferSize) {
        _internalBuffer = new char[initialBufferSize];
        clearBuffer();
    }

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
    final protected void putChar(char c) {
        final int clen = _internalBuffer.length;
        if (clen == _bufferPosition) {
            final char[] next = new char[2 * clen + 1];

            System.arraycopy(_internalBuffer, 0, next, 0, _bufferPosition);
            _internalBuffer = next;
        }

        _internalBuffer[_bufferPosition++] = c;
    }

    final protected int bufferPosition() {
        return _bufferPosition;
    }

    final protected void clearBuffer() {
        _bufferPosition = 0;
    }

    final protected String getString() {
        return getString(0, _bufferPosition);
    }

    final protected String getString(int end) {
        return getString(0, end);
    }

    final protected String getString(int start, int end) {
        if (end > _bufferPosition) {
            throw new IndexOutOfBoundsException("Requested: " + end + ", max: " + _bufferPosition);
        }

        String str = new String(_internalBuffer, start, end);
        return str;
    }

    /** Returns the string in the buffer minus an leading or trailing whitespace or quotes */
    final protected String getTrimmedString() throws BaseExceptions.BadRequest {
        if (_bufferPosition == 0) return "";

        int start = 0;
        boolean quoted = false;
        // Look for start
        while (start < _bufferPosition) {
            final char ch = _internalBuffer[start];
            if (ch == '"') {
                quoted = true;
                break;
            }
            else if (ch != HttpTokens.SPACE && ch != HttpTokens.TAB) {
                break;
            }
            start++;
        }

        int end = _bufferPosition;  // Position is of next write

        // Look for end
        while(end > start) {
            final char ch = _internalBuffer[end - 1];

            if (quoted) {
                if (ch == '"') break;
                else if (ch != HttpTokens.SPACE && ch != HttpTokens.TAB) {
                    throw new BaseExceptions.BadRequest("String might not quoted correctly: '" + getString() + "'");
                }
            }
            else if (ch != HttpTokens.SPACE && ch != HttpTokens.TAB) break;
            end--;
        }

        String str = new String(_internalBuffer, start, end - start);

        return str;
    }

    final protected boolean arrayMatches(final char[] chars) {
        if (chars.length != _bufferPosition) return false;

        for (int i = 0; i < _bufferPosition; i++) {
            if (chars[i] != _internalBuffer[i])
                return false;
        }

        return true;
    }

    /* ------------------------------------------------------------------- */

    final protected void resetLimit(int limit) {
        _segmentByteLimit = limit;
        _segmentBytePosition = 0;
    }

    // Removes CRs but returns LFs
    final protected char next(final ByteBuffer buffer, boolean allow8859) throws BaseExceptions.BadRequest {

        if (!buffer.hasRemaining()) return 0;

        if (_segmentByteLimit <= _segmentBytePosition) {
            shutdownParser();
            throw new BadRequest("Request length limit exceeded: " + _segmentByteLimit);
        }

        final byte b = buffer.get();
        _segmentBytePosition++;

        // If we ended on a CR, make sure we are
        if (_cr) {
            if (b != HttpTokens.LF) {
                throw new BadRequest("Invalid sequence: LF didn't follow CR: " + b);
            }
            _cr = false;
            return (char)b;  // must be LF
        }

        // Make sure its a valid character
        if (b < HttpTokens.SPACE) {
            if (b == HttpTokens.CR) {   // Set the flag to check for _cr and just run again
                _cr = true;
                return next(buffer, allow8859);
            }
            else if (b == HttpTokens.TAB || allow8859 && b < 0) {
                return (char)(b & 0xff);
            }
            else {
                if (b == HttpTokens.LF) {
                    shutdownParser();
                    throw new BadRequest("LineFeed found without CR");
                }
                else {
                    shutdownParser();
                    throw new BadRequest("Invalid char: '" + (char)(b & 0xff) + "', 0x" + Integer.toHexString(b));
                }
            }
        }

        // valid ascii char
        return (char)b;
    }
}
