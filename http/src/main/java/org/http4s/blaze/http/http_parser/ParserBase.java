package org.http4s.blaze.http.http_parser;


import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.http4s.blaze.http.http_parser.BaseExceptions.BadRequest;

/**
 * @author Bryce Anderson
 *         Created on 2/4/14
 */
public abstract class ParserBase {

    // Methods for managing the internal buffer

    ParserBase(int initialSize) {
        _internalBuffer = new byte[initialSize];
        clearBuffer();
    }

    private int _bufferPosition = 0;
    private int _bufferLen = 0;
    private byte[] _internalBuffer;

    private boolean _cr;
    private int _segmentByteLimit;
    private int _segmentBytePosition;

    /** for shutting down the parser and its state */
    public void shutdownParser() {
        clearBuffer();
    }

    void reset() {
       clearBuffer();
    }

    final protected void makeRoom(int size) {
        // Resize the internal array
        int nextsize = Math.max(_bufferLen*2, _bufferLen + 2*size);
        this._bufferLen = nextsize;
        byte[] next = new byte[nextsize];

        System.arraycopy(_internalBuffer, 0, next, 0, _bufferPosition);
        _internalBuffer = next;
    }

    final protected void putByte(byte c) {
        if (_internalBuffer.length == _bufferPosition) {
            makeRoom(1);
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
        String str = new String(_internalBuffer, start, end, StandardCharsets.US_ASCII);
        return str;
    }

    /** Returns the string in the buffer minus an leading or trailing whitespace or quotes */
    final protected String getTrimmedString() throws BaseExceptions.BadRequest {
        if (_bufferPosition == 0) return "";

        int start = 0;
        boolean quoted = false;
        // Look for start
        while (start < _bufferPosition) {
            final byte ch = _internalBuffer[start];
            if (ch == '"') {
                quoted = true;
                break;
            }
            else if (ch != HttpTokens.SPACE && ch != HttpTokens.TAB) {
                break;
            }
            start++;
        }

        int end = _bufferPosition - 1;  // Position is of next write

        // Look for end
        while(end > start) {
            final byte ch = _internalBuffer[end];
            if (quoted) {
                if (ch == '"') break;
                else if (ch != HttpTokens.SPACE && ch != HttpTokens.TAB) {
                    throw new BaseExceptions.BadRequest("String might not quoted correctly: '" + getString() + "'");
                }
            }
            else if (ch != HttpTokens.SPACE && ch != HttpTokens.TAB) break;
            end--;
        }

        String str = new String(_internalBuffer, start, end + 1, StandardCharsets.US_ASCII);
        return str;
    }

    final protected boolean arrayMatches(final byte[] bytes) {
        if (bytes.length != _bufferPosition) return false;

        for (int i = 0; i < _bufferPosition; i++) {
            if (bytes[i] != _internalBuffer[i])
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
    final protected byte next(final ByteBuffer buffer) throws BaseExceptions.BadRequest {

        if (!buffer.hasRemaining()) return 0;

        if (_segmentByteLimit == _segmentBytePosition) {
            shutdownParser();
            throw new BadRequest("Request length limit exceeded: " + _segmentByteLimit);
        }

        final byte ch = buffer.get();
        _segmentBytePosition++;

        // If we ended on a CR, make sure we are
        if (_cr) {
            if (ch != HttpTokens.LF) {
                throw new BadRequest("Invalid sequence: LF didn't follow CR: " + ch);
            }
            _cr = false;
            return ch;
        }

        // Make sure its a valid character
        if (ch < HttpTokens.SPACE) {
            if (ch == HttpTokens.CR) {   // Set the flag to check for _cr and just run again
                _cr = true;
                return next(buffer);
            }
            else if (ch == HttpTokens.TAB) {
                return ch;
            }
            else {
                if (ch == HttpTokens.LF) {
                    shutdownParser();
                    throw new BadRequest("LineFeed found without CR");
                }
                else {
                    shutdownParser();
                    throw new BadRequest("Invalid char: " + ch);
                }
            }
        }

        return ch;
    }
}
