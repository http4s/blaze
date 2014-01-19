package blaze.http_parser;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import blaze.http_parser.BaseExceptions.*;
import blaze.http_parser.HttpTokens.EndOfContent;

import java.nio.charset.StandardCharsets;


/**
 * @author Bryce Anderson
 *         Created on 1/2/14
 */
public abstract class Http1Parser {

    // Lets us call the shutdown hooks
    private class ParserBadRequest extends BadRequest {
        private ParserBadRequest(String msg) {
            super(msg);
            shutdownParser();
        }
    }

    private class ParserInvalidState extends InvalidState {
        private ParserInvalidState(String msg) {
            super(msg);
            shutdownParser();
        }
    }

    public final static Charset ASCII = StandardCharsets.US_ASCII;

    protected static byte[] HTTP10Bytes  = "HTTP/1.0".getBytes(ASCII);
    protected static byte[] HTTP11Bytes  = "HTTP/1.1".getBytes(ASCII);

    protected static byte[] HTTPS10Bytes = "HTTPS/1.0".getBytes(ASCII);
    protected static byte[] HTTPS11Bytes = "HTTPS/1.1".getBytes(ASCII);

    private static ByteBuffer emptyBuffer = ByteBuffer.allocate(0);

    private enum LineState {
        START,
        METHOD,
        SPACE1,
        URI,
        SPACE2,
        REQUEST_VERSION,
        END,
    }

    private enum HeaderState {
        START,
        HEADER_IN_NAME,
        HEADER_SPACE,
        HEADER_IN_VALUE,
        END
    }

    private enum ChunkState {
        START,
        CHUNK_SIZE,
        CHUNK_PARAMS,
        CHUNK,
        CHUNK_LF,
        CHUNK_TRAILERS,
        END
    }

    private final int maxRequestLineSize;
    private final int headerSizeLimit;
    private final int maxChunkSize;

    /* ------------------------------------------------------------------- */
    private LineState _lineState = LineState.START;
    private HeaderState _hstate = HeaderState.START;

    private String _methodString;
    private String _uriString;
    private boolean _hostRequired;
    private String _headerName;
    private EndOfContent _endOfContent;
    private long _contentLength;
    private long _contentPosition;
    private ChunkState _chunkState;
    private int _chunkLength;
    private int _chunkPosition;
    private boolean _cr;
    private int _segmentByteLimit;
    private int _segmentBytePosition;


    /* ------------------------------------------------------------------ */

    /**
     * This is the method called by parser when the HTTP request line is parsed
     * @param methodString The method as a string
     * @param uri The raw bytes of the URI.  These are copied into a ByteBuffer that will not be changed until this parser is reset and reused.
     * @param majorversion major version
     * @param minorversion minor version
     * @return true if handling parsing should return.
     */
    public abstract void submitRequestLine(String methodString, String uri, String scheme, int majorversion, int minorversion);

    /**
     * This is the method called by parser when a HTTP Header name and value is found
     * @param name The name of the header
     * @param value The value of the header
     * @return True if the parser should return to its caller
     */
    public abstract void headerComplete(String name, String value) throws BadRequest;


    /* ------------------------------------------------------------------ */

    public final boolean requestLineComplete() {
        return _lineState == LineState.END;
    }

    public final boolean headersComplete() {
        return _hstate == HeaderState.END;
    }

    public final boolean contentComplete() {
        return _endOfContent == EndOfContent.EOF_CONTENT || _endOfContent == EndOfContent.NO_CONTENT;
    }

    public final boolean isChunked() {
        return _endOfContent == HttpTokens.EndOfContent.CHUNKED_CONTENT;
    }

    public final boolean inChunkedHeaders() {
        return _chunkState == ChunkState.CHUNK_TRAILERS;
    }

    public final boolean definedContentLength() {
        return _endOfContent == HttpTokens.EndOfContent.CONTENT_LENGTH;
    }

    public final HttpTokens.EndOfContent getContentType() {
        return _endOfContent;
    }

    /* ------------------------------------------------------------------ */

    public final void reset() {

        clearBuffer();

        _lineState = LineState.START;
        _hstate = HeaderState.START;
        _chunkState = ChunkState.START;

        _endOfContent = EndOfContent.UNKNOWN_CONTENT;

        _contentLength = 0;
        _contentPosition = 0;
        _chunkLength = 0;
        _chunkPosition = 0;
        _hostRequired = true;
    }

    /* ------------------------------------------------------------------ */

    protected void shutdownParser() {
        _lineState = LineState.END;
        _hstate = HeaderState.END;
        _chunkState = ChunkState.END;
        _endOfContent = EndOfContent.EOF_CONTENT;
    }

    /* ------------------------------------------------------------------ */
    // Methods for managing the internal buffer

    private int _bufferPosition = 0;
    private int _bufferLen = 0;
    private byte[] _internalBuffer;

    private void makeRoom(int size) {
        // Resize the internal array
        int nextsize = Math.max(_bufferLen*2, _bufferLen + 2*size);
        this._bufferLen = nextsize;
        byte[] next = new byte[nextsize];

        System.arraycopy(_internalBuffer, 0, next, 0, _bufferPosition);
        _internalBuffer = next;
    }

    private void putByte(byte c) {
        if (_internalBuffer.length == _bufferPosition) {
            makeRoom(1);
        }
        _internalBuffer[_bufferPosition++] = c;
    }

    private int bufferPosition() {
        return _bufferPosition;
    }

    private void clearBuffer() {
        _bufferPosition = 0;
    }

    private String getString() {
        return getString(0, _bufferPosition);
    }

    private String getString(int end) {
        return getString(0, end);
    }

    private String getString(int start, int end) {
        String str = new String(_internalBuffer, start, end, ASCII);
        return str;
    }

    /** Returns the string in the buffer minus an leading or trailing whitespace or quotes */
    private String getTrimmedString() throws BadRequest {

        if (_bufferPosition == 0) return "";

        int start = 0;
        // Look for start
        while (start < _bufferPosition) {
            final byte ch = _internalBuffer[start];
            if (ch != HttpTokens.SPACE && ch != HttpTokens.TAB && ch != '"') {
                break;
            }
            start++;
        }

        int end = _bufferPosition - 1;  // Position is of next write

        // Look for end
        while(end > start) {
            final byte ch = _internalBuffer[end];
            if (ch != HttpTokens.SPACE && ch != HttpTokens.TAB && ch != '"') {
                break;
            }
            end--;
        }

        if (end == start) {
            throw new ParserBadRequest("String might not quoted correctly: '" + getString() + "'");
        }

        String str = new String(_internalBuffer, start, end + 1, ASCII);
        return str;
    }

    private boolean arrayMatches(final byte[] bytes) {
        if (bytes.length != _bufferPosition) return false;

        for (int i = 0; i < _bufferPosition; i++) {
            if (bytes[i] != _internalBuffer[i])
                return false;
        }

        return true;
    }

    /** Manages the buffer position while submitting the content -------- */

    private ByteBuffer submitBuffer(ByteBuffer in) {
        ByteBuffer out = in.asReadOnlyBuffer();
        in.position(in.limit());
        return out;
    }

    private ByteBuffer submitPartialBuffer(ByteBuffer in, int size) {
        // Perhaps we are just right? Might be common.
        if (size == in.remaining()) {
            return submitBuffer(in);
        }

        final int old_lim = in.limit();
        final int end = in.position() + size;

        // Make a slice buffer and return its read only image
        in.limit(end);
        ByteBuffer b = in.slice().asReadOnlyBuffer();
        // fast forward our view of the data
        in.limit(old_lim);
        in.position(end);
        return b;
    }

    /* ------------------------------------------------------------------ */
    // the sole Constructor

    public Http1Parser(int maxReqLen, int maxHeaderLength, int initialBufferSize, int maxChunkSize) {
        this.maxRequestLineSize = maxReqLen;
        this.headerSizeLimit = maxHeaderLength;
        this.maxChunkSize = maxChunkSize;

        _internalBuffer = new byte[initialBufferSize];
        reset();
    }

    public Http1Parser(int maxReqLen, int maxHeaderLength, int initialBufferSize) {
        this(maxReqLen, maxHeaderLength, initialBufferSize, Integer.MAX_VALUE);
    }

    public Http1Parser(int initialBufferSize) {
        this(2048, 40*1024, initialBufferSize);
    }

    public Http1Parser() { this(10*1024); }

    /* ------------------------------------------------------------------ */

    private void resetLimit(int limit) {
        _segmentByteLimit = limit;
        _segmentBytePosition = 0;
    }

    // Removes CRs but returns LFs
    private byte next(final ByteBuffer buffer) throws BadRequest {

        if (!buffer.hasRemaining()) return 0;

        if (_segmentByteLimit == _segmentBytePosition) {
            throw new ParserBadRequest("Request length limit exceeded: " + _segmentByteLimit);
        }

        final byte ch = buffer.get();
        _segmentBytePosition++;

        // If we ended on a CR, make sure we are
        if (_cr) {
            if (ch != HttpTokens.LF) {
                throw new ParserBadRequest("Invalid sequence: LF didn't follow CR: " + ch);
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
                    throw new ParserBadRequest("LineFeed found without CR");
                }
                else {
                    throw new ParserBadRequest("Invalid char: " + ch);
                }
            }
        }

        return ch;
    }

    /* ------------------------------------------------------------------ */

    /** parses the request line. Returns true if completed successfully, false if needs input */
    protected final boolean parseRequestLine(ByteBuffer in) throws InvalidState, BadRequest {
        lineLoop: while(true) {
            byte ch;
            switch (_lineState) {
                case START:
                    _lineState = LineState.METHOD;
                    resetLimit(maxRequestLineSize);

                case METHOD:
                    for(ch = next(in); HttpTokens.A <= ch && ch <= HttpTokens.Z; ch = next(in)) {
                        putByte(ch);
                    }

                    if (ch == 0) return false;

                    _methodString = getString();
                    clearBuffer();


                    if (!HttpTokens.isWhiteSpace(ch)) {
                        String badmethod = _methodString + (char)ch;
                        throw new ParserBadRequest("Invalid request method: '" + badmethod + "'");
                    }

                   _lineState = LineState.SPACE1;

                case SPACE1:
                    // Eat whitespace
                    for(ch = next(in); ch == HttpTokens.SPACE || ch == HttpTokens.TAB; ch = next(in));

                    if (ch == 0) return false;

                    putByte(ch);
                    _lineState = LineState.URI;

                case URI:
                    for(ch = next(in); ch != HttpTokens.SPACE && ch != HttpTokens.TAB; ch = next(in)) {
                        if (ch == 0) return false;
                        putByte(ch);
                    }

                    _uriString = getString();
                    clearBuffer();

                    if (!HttpTokens.isWhiteSpace(ch)) {
                        String baduri = _uriString + (char)ch;
                        throw new ParserBadRequest("Invalid request URI: '" + baduri + "'");
                    }

                    _lineState = LineState.SPACE2;

                case SPACE2:
                    // Eat whitespace
                    for(ch = next(in); ch == HttpTokens.SPACE || ch == HttpTokens.TAB; ch = next(in));

                    if (ch == 0) return false;

                    if (ch != 'H') throw new ParserBadRequest("Http version started with illegal character: " + 'c');

                    putByte(ch);
                    _lineState = LineState.REQUEST_VERSION;

                case REQUEST_VERSION:
                    for(ch = next(in); ch != HttpTokens.LF; ch = next(in)) {
                        if (ch == 0) return false;
                        putByte(ch);
                    }

                    int _majorversion = 1;
                    int _minorversion = 1;

                    if (arrayMatches(HTTP11Bytes) || arrayMatches(HTTPS11Bytes)) {
                    // NOOP, already set to this
//                        _majorversion = 1;
//                        _minorversion = 1;
                    }
                    else if (arrayMatches(HTTP10Bytes) || arrayMatches(HTTPS10Bytes)) {
                        _minorversion = 0;
                        _hostRequired = false;
                    }
                    else {
                        String reason =  "Bad HTTP version: " + getString();
                        clearBuffer();
                        throw new ParserBadRequest(reason);
                    }

                    String scheme = getString(bufferPosition() - 4);
                    clearBuffer();

                    // We are through parsing the request line
                    _lineState = LineState.END;
                    submitRequestLine(_methodString, _uriString, scheme, _majorversion, _minorversion);
                    return true;

                default:
                    throw new InvalidState("Attempted to parse Request line when already complete." +
                                                  "LineState: '" + _lineState + "'");
            }    // switch
        }        // while loop
    }

    protected final boolean parseHeaders(ByteBuffer in) throws BadRequest, InvalidState {

        headerLoop: while (true) {
            byte ch;
            switch (_hstate) {
                case START:
                    _hstate = HeaderState.HEADER_IN_NAME;
                    resetLimit(headerSizeLimit);

                case HEADER_IN_NAME:
                    for(ch = next(in); ch != ':' && ch != HttpTokens.LF; ch = next(in)) {
                        if (ch == 0) return false;
                        putByte(ch);
                    }

                    // Must be done with headers
                    if (bufferPosition() == 0) {

                        if (_hostRequired) {
                            // If we didn't get our host header, we have a problem.
                            throw new ParserBadRequest("Missing host header");
                        }

                        _hstate = HeaderState.END;

                        // Finished with the whole request
                        if (_chunkState == ChunkState.CHUNK_TRAILERS) shutdownParser();

                        // TODO: perhaps we should test against if it is GET, OPTION, or HEAD.
                        else if ((_endOfContent == EndOfContent.UNKNOWN_CONTENT &&
                                 _methodString != null   &&
                                 _methodString != "POST" &&
                                 _methodString != "PUT")) shutdownParser();

                        // Done parsing headers
                        return true;
                    }

                    if (ch == HttpTokens.LF) {  // Valueless header
                        String name = getString();
                        clearBuffer();

                        headerComplete(name, "");

                        continue headerLoop;    // Still parsing Header name
                    }

                    _headerName = getString();
                    clearBuffer();
                    _hstate = HeaderState.HEADER_SPACE;

                case HEADER_SPACE:
                    for(ch = next(in); ch == HttpTokens.SPACE || ch == HttpTokens.TAB; ch = next(in));

                    if (ch == 0) return false;

                    if (ch == HttpTokens.LF) {
                        throw new ParserBadRequest("Missing value for header " + _headerName);
                    }

                    putByte(ch);
                    _hstate = HeaderState.HEADER_IN_VALUE;

                case HEADER_IN_VALUE:
                    for(ch = next(in); ch != HttpTokens.LF; ch = next(in)) {
                        if (ch == 0) return false;
                        putByte(ch);
                    }

                    String value = getTrimmedString();//getString(); //getTrimmedString();
                    clearBuffer();

                    // If we are not parsing trailer headers, look for some that are of interest to the request
                    if (_chunkState != ChunkState.CHUNK_TRAILERS) {

                        // Check for host if it is still needed
                        if (_hostRequired && _headerName.equalsIgnoreCase("Host")) {
                            _hostRequired = false;  // Don't search for the host header anymore
//                            _host = value;
                        }

                        // Check for submitContent type if its still not determined
                        if (_endOfContent == EndOfContent.UNKNOWN_CONTENT) {
                            if (_headerName.equalsIgnoreCase("Transfer-Encoding")) {
                                if (!value.equalsIgnoreCase("chunked")) {
                                    throw new ParserBadRequest("Unknown Transfer-Encoding: " + value);
                                }

                                _endOfContent = EndOfContent.CHUNKED_CONTENT;
                            }
                            else if (_headerName.equalsIgnoreCase("Content-Length")) {
                                try {
                                    _contentLength = Long.parseLong(value);
                                }
                                catch (NumberFormatException t) {
                                    throw new ParserBadRequest("Invalid Content-Length: '" + value + "'\n");
                                }

                                _endOfContent = _contentLength <= 0 ?
                                        EndOfContent.NO_CONTENT:EndOfContent.CONTENT_LENGTH;
                            }
                        }
                    }

                    // Send off the header and see if we wish to continue
                    try {
                        headerComplete(_headerName, value);
                    } finally {
                        _hstate = HeaderState.HEADER_IN_NAME;
                    }

                    break;

                case END:
                    throw new ParserInvalidState("Header parser reached invalid position.");
            }   // Switch
        }   // while loop

    }

    protected final ByteBuffer parseContent(ByteBuffer in) throws ParserException {
        switch (_endOfContent) {
            case UNKNOWN_CONTENT:
                // Need Content-Length or Transfer-Encoding to signal a body for GET
                // rfc2616 Sec 4.4 for more info
                // What about custom verbs which may have a body?
                // We could also CONSIDER doing a BAD Request here.

                _endOfContent = EndOfContent.SELF_DEFINING_CONTENT;
                return parseContent(in);

            case CONTENT_LENGTH:
                return nonChunkedContent(in);

            case CHUNKED_CONTENT:
                    return chunkedContent(in);

            case SELF_DEFINING_CONTENT:

            default:
                throw new InvalidState("not implemented: " + _endOfContent);
        }
    }

    private ByteBuffer nonChunkedContent(ByteBuffer in) {
        final long remaining = _contentLength - _contentPosition;
        final int buf_size = in.remaining();

        if (buf_size >= remaining) {
            _contentPosition += remaining;
            ByteBuffer result = submitPartialBuffer(in, (int)remaining);
            shutdownParser();
            return result;
        }
        else {
            _contentPosition += buf_size;
            return submitBuffer(in);
        }
    }

    private ByteBuffer chunkedContent(ByteBuffer in) throws BadRequest, InvalidState {
        while(true) {
            byte ch;
            sw: switch (_chunkState) {
                case START:
                    _chunkState = ChunkState.CHUNK_SIZE;
                    // Don't want the chunk size and extension field to be too long.
                    resetLimit(256);

                case CHUNK_SIZE:
                    assert _chunkPosition == 0;

                    while (true) {

                        ch = next(in);
                        if (ch == 0) return null;

                        if (HttpTokens.isWhiteSpace(ch) || ch == HttpTokens.SEMI_COLON) {
                            _chunkState = ChunkState.CHUNK_PARAMS;
                            break;  // Break out of the while loop, and fall through to params
                        }
                        else if (ch == HttpTokens.LF) {
                            if (_chunkLength == 0) {
                                _hstate = HeaderState.START;
                                _chunkState = ChunkState.CHUNK_TRAILERS;
                            }
                            else _chunkState = ChunkState.CHUNK;

                            break sw;
                        }
                        else {
                            _chunkLength = 16 * _chunkLength + HttpTokens.hexCharToInt(ch);

                            if (_chunkLength > maxChunkSize) {
                                throw new ParserBadRequest("Chunk length too large: " + _chunkLength);
                            }
                        }
                    }

                case CHUNK_PARAMS:
                    // Don't store them, for now.
                    for(ch = next(in); ch != HttpTokens.LF; ch = next(in)) {
                        if (ch == 0) return null;
                    }

                    // Check to see if this was the last chunk
                    if (_chunkLength == 0) {
                        _hstate = HeaderState.START;
                        _chunkState = ChunkState.CHUNK_TRAILERS;
                    }
                    else _chunkState = ChunkState.CHUNK;
                    break;

                case CHUNK:
                    final int remaining_chunk_size =  _chunkLength - _chunkPosition;
                    final int chunk_size = in.remaining();

                    if (remaining_chunk_size <= chunk_size) {
                        ByteBuffer result = submitPartialBuffer(in, remaining_chunk_size);
                        _chunkPosition = _chunkLength = 0;
                        _chunkState = ChunkState.CHUNK_LF;
                        return result;
                    }
                    else {
                        _chunkPosition += chunk_size;
                        return submitBuffer(in);
                    }

                case CHUNK_LF:
                    ch = next(in);
                    if (ch == 0) return null;

                    if (ch != HttpTokens.LF) {
                        throw new ParserBadRequest("Bad chunked encoding char: '" + (char)ch + "'");
                    }

                    _chunkState = ChunkState.START;
                    break;


                case CHUNK_TRAILERS:    // more headers
                    parseHeaders(in);
                    return null;
            }
        }
    }
}
