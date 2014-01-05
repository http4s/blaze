package blaze.http_parser;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import blaze.http_parser.BaseExceptions.*;
import blaze.http_parser.HttpTokens.EndOfContent;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;


/**
 * @author Bryce Anderson
 *         Created on 1/2/14
 */
public abstract class RequestParser {

    public final static Charset ASCII = Charset.forName("US-ASCII");

    protected static byte[] HTTP10Bytes = "HTTP/1.0".getBytes(ASCII);
    protected static byte[] HTTPS10Bytes = "HTTPS/1.0".getBytes(ASCII);

    protected static byte[] HTTP11Bytes = "HTTP/1.1".getBytes(ASCII);
    protected static byte[] HTTPS11Bytes = "HTTPS/1.1".getBytes(ASCII);

    // States
    public enum State {
        START,
        METHOD,
        SPACE1,
        URI,
        SPACE2,
        REQUEST_VERSION,
//        RESPONSE_VERSION,
//        STATUS,
//        REASON,
        HEADER,
        CONTENT,
        END,
    }

    public enum HeaderState {
        START,
        HEADER_IN_NAME,
        HEADER_SPACE,
        HEADER_IN_VALUE,
        END
    }

    public enum ChunkState {
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
    private volatile State _state=State.START;
    private HeaderState _hstate = HeaderState.START;

    private String _methodString;
    private String _uriString;
    private boolean _hostRequired;
//    private String _host = null;
    private String _headerName;
    private EndOfContent _endOfContent;
    private long _contentLength;
    private long _contentPosition;
    private ChunkState _chunkState;
    private int _chunkLength;
    private int _chunkPosition;
//    private boolean _headResponse;
    private boolean _cr;
//    private ByteBuffer _contentChunk;
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
    public abstract boolean startRequest(String methodString, String uri, String scheme, int majorversion, int minorversion);

    /** take content from the parser.
     *
     * @param buffer The ByteBuffer containing the appropriate data.
     * @return true if successful and false of there was a problem.
     * - If the read was successful, it is assumed that the whole Buffer was taken
     * - If it was unsuccessful, it is assumed that the buffer is unchanged, and may
     *   be resubmitted
     */
    public abstract boolean submitContent(ByteBuffer buffer);

    public abstract void headersComplete() throws Exception;

    public abstract void requestComplete();

    /**
     * This is the method called by parser when a HTTP Header name and value is found
     * @param name The name of the header
     * @param value The value of the header
     * @return True if the parser should return to its caller
     */
    public abstract void headerComplete(String name, String value) throws Exception;

        /* ------------------------------------------------------------ */
    /** Called to signal that an EOF was received unexpectedly
     * during the parsing of a HTTP message
     */
    public abstract void earlyEOF() throws Exception;

    /* ------------------------------------------------------------------ */

    public final State getState() {
        return _state;
    }

    public final boolean inRequestLine() {
        return _state.ordinal() < State.HEADER.ordinal();
    }

    public final boolean inHeaders() {
        return _state.ordinal() >= State.HEADER.ordinal() &&
               _state.ordinal() < State.CONTENT.ordinal() &&
               _hstate != HeaderState.END;
    }

    public final boolean inContent() {
        return _state == State.CONTENT;
    }

    public final boolean inDefinedContent() {
        return inContent() && _endOfContent == EndOfContent.CONTENT_LENGTH;
    }

    public final boolean inChunked() {
        return inContent() &&
                _endOfContent == EndOfContent.CHUNKED_CONTENT;
    }

    public final boolean inChunkedHeaders() {
        return _state == State.CONTENT &&
               _hstate != HeaderState.START &&
               _hstate != HeaderState.END;
    }

    public final boolean finished() {
        return _state == State.END;
    }

    public EndOfContent getContentType() {
        return _endOfContent;
    }

    public void reset() {
        clearBuffer();

        _state = State.START;
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

    protected void shutdown() {
        requestComplete();

        _state = State.END;
        _hstate = HeaderState.END;
        _chunkState = ChunkState.END;
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
    private String getTrimmedString() throws ParsingError {

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
            error(new ParsingError("String might not quoted correctly: '" + getString() + "'"));
        }

        String str = new String(_internalBuffer, start, end + 1, ASCII);
        return str;
    }

    private boolean arrayMatches(byte[] bytes) {
        if (bytes.length != _bufferPosition) return false;

        for (int i = 0; i < _bufferPosition; i++) {
            if (bytes[i] != _internalBuffer[i])
                return false;
        }

        return true;
    }

    /* ------------------------------------------------------------------ */
    // the sole Constructor

    public RequestParser(int maxReqLen, int maxHeaderLength, int initialBufferSize, int maxChunkSize) {
        this.maxRequestLineSize = maxReqLen;
        this.headerSizeLimit = maxHeaderLength;
        this.maxChunkSize = maxChunkSize;

        _internalBuffer = new byte[initialBufferSize];
        reset();
    }

    public RequestParser(int maxReqLen, int maxHeaderLength, int initialBufferSize) {
        this(maxReqLen, maxHeaderLength, initialBufferSize, Integer.MAX_VALUE);
    }

    public RequestParser(int initialBufferSize) {
        this(2048, 40*1024, initialBufferSize, Integer.MAX_VALUE);
    }

    public RequestParser() { this(4*1024); }

    /* ------------------------------------------------------------------ */

    private void setLimit(int limit) {
        _segmentByteLimit = limit;
        _segmentBytePosition = 0;
    }

    // Removes CRs but returns LFs
    private byte next(final ByteBuffer buffer) throws ParsingError {

        if (!buffer.hasRemaining()) return 0;

        if (_segmentByteLimit == _segmentBytePosition) {
            throw new ParsingError("Request length limit exceeded: " + _segmentByteLimit);
        }

        final byte ch = buffer.get();
        _segmentBytePosition++;

        // If we ended on a CR, make sure we are
        if (_cr) {
            if (ch != HttpTokens.LF) {
                throw new ParsingError("Invalid sequence: LF didn't follow CR: " + ch);
            }

            _cr = false;
            return ch;
        }

        // Make sure its a valid character
        if (ch < HttpTokens.SPACE) {
            if (ch == HttpTokens.CR) {
                if (!buffer.hasRemaining()) {
                    _cr = true;
                    return 0;
                }

                final byte lf = buffer.get();
                if (lf != HttpTokens.LF) {
                    throw new ParsingError("Invalid sequence: LF without preceeding CR: " + lf);
                }
                else {
                    return lf;
                }
            }
            else if (ch == HttpTokens.TAB) {
                return ch;
            }
            else {
                if (ch == HttpTokens.LF) {
                    throw new ParsingError("LineFeed found without CR");
                }
                else {
                    throw new ParsingError("Invalid char: " + ch);
                }
            }
        }

        return ch;
    }

    /* ------------------------------------------------------------------ */

    private boolean error(ParsingError e) throws ParsingError {
        shutdown();
        throw e;
    }

    private boolean error(BadRequest e) throws BadRequest {
        shutdown();
        throw e;
    }


//    private void checkMethod(String method) throws ParserException {
//        if (method.equalsIgnoreCase("GET")) {
//            _endOfContent = EndOfContent.NO_CONTENT;
//        }
//    }

    protected final void setState(State state) {
        _state = state;
    }

    protected final boolean parseRequestLine(ByteBuffer in) throws ParserException {
        lineLoop: while(true) {
            byte ch;
            switch (_state) {
                case START:
                    _state = State.METHOD;
                    setLimit(maxRequestLineSize);

                case METHOD:
                    for(ch = next(in); HttpTokens.A <= ch && ch <= HttpTokens.Z; ch = next(in)) {
                        putByte(ch);
                    }

                    if (ch == 0) return true;

                    _methodString = getString();
                    clearBuffer();


                    if (!HttpTokens.isWhiteSpace(ch)) {
                        String badmethod = _methodString + (char)ch;
                        error(new BadRequest(400, "Invalid request method: '" + badmethod + "'"));
                    }

                    setState(State.SPACE1);

                case SPACE1:
                    // Eat whitespace
                    for(ch = next(in); ch == HttpTokens.SPACE || ch == HttpTokens.TAB; ch = next(in));

                    if (ch == 0) return true;

                    putByte(ch);
                    setState(State.URI);

                case URI:
                    for(ch = next(in); ch != HttpTokens.SPACE && ch != HttpTokens.TAB; ch = next(in)) {
                        if (ch == 0) return true;
                        putByte(ch);
                    }

                    _uriString = getString();
                    clearBuffer();

                    if (!HttpTokens.isWhiteSpace(ch)) {
                        String baduri = _uriString + (char)ch;
                        error(new BadRequest(400, "Invalid request URI: '" + baduri + "'"));
                    }

                    setState(State.SPACE2);

                case SPACE2:
                    // Eat whitespace
                    for(ch = next(in); ch == HttpTokens.SPACE || ch == HttpTokens.TAB; ch = next(in));

                    if (ch == 0) return true;

                    if (ch != 'H') {
                        error(new ParsingError("Http version started with illegal character: " + 'c'));
                    }

                    putByte(ch);
                    setState(State.REQUEST_VERSION);

                case REQUEST_VERSION:
                    for(ch = next(in); ch != HttpTokens.LF; ch = next(in)) {
                        if (ch == 0) return true;
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
                        error(new BadRequest(400, reason));
                    }

                    String scheme = getString(bufferPosition() - 4);
                    clearBuffer();

                    // We are through parsing the request line
                    setState(State.HEADER);
                    return startRequest(_methodString, _uriString, scheme, _majorversion, _minorversion);

                default:
                    return error(new ParsingError("Attempted to parse Request line when already complete." +
                                                  "State: '" + _state + "'"));
            }    // switch
        }        // while loop
    }

    protected final boolean parseHeaders(ByteBuffer in) throws ParserException {
        headerLoop: while (true) {
            byte ch;
            switch (_hstate) {
                case START:
                    _hstate = HeaderState.HEADER_IN_NAME;
                    setLimit(headerSizeLimit);

                case HEADER_IN_NAME:
                    for(ch = next(in); ch != ':' && ch != HttpTokens.LF; ch = next(in)) {
                        if (ch == 0) return true;
                        putByte(ch);
                    }

                    // Must be done with headers
                    if (bufferPosition() == 0) {

                        if (_hostRequired) {
                            // If we didn't get our host header, we have a problem.
                            error(new BadRequest(400, "Missing host header"));
                        }

                        // Notify the handler we are finished with this batch of headers
                        try {
                            headersComplete();
                        } catch (ParserException e) {
                            throw e;
                        } catch (Exception e) {
                            shutdown();
                            throw new ExternalExeption(e, "header completion");
                        } finally {
                            _hstate = HeaderState.END;

                            // Finished with the whole request
                            if (_chunkState == ChunkState.CHUNK_TRAILERS) shutdown();
                            else {    // now doing the body if we have one
                                if (_endOfContent == EndOfContent.UNKNOWN_CONTENT &&
                                    _methodString != "POST" &&
                                    _methodString != "PUT") setState(State.END);

                                else if (_endOfContent != EndOfContent.NO_CONTENT) setState(State.CONTENT);
                                else setState(State.END);
                            }
                        }

                        // Done parsing headers
                        return true;
                    }

                    if (ch == HttpTokens.LF) {  // Valueless header
                        String name = getString();
                        clearBuffer();

                        try {
                            headerComplete(name, "");
                        } catch (ParserException e) {
                            throw e;
                        } catch (Exception e) {
                            shutdown();
                            throw new ExternalExeption(e, "valueless header submission: '" + name + "'");
                        }

                        continue headerLoop;    // Still parsing Header name
                    }

                    _headerName = getString();
                    clearBuffer();
                    _hstate = HeaderState.HEADER_SPACE;

                case HEADER_SPACE:
                    for(ch = next(in); ch == HttpTokens.SPACE || ch == HttpTokens.TAB; ch = next(in));

                    if (ch == 0) return true;

                    if (ch == HttpTokens.LF) {
                        return error(new ParsingError("Missing value for header " + _headerName));
                    }

                    putByte(ch);
                    _hstate = HeaderState.HEADER_IN_VALUE;

                case HEADER_IN_VALUE:
                    for(ch = next(in); ch != HttpTokens.LF; ch = next(in)) {
                        if (ch == 0) return true;
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
                                    error(new BadRequest(400, "Unknown Transfer-Encoding: " + value));
                                }
                                _endOfContent = EndOfContent.CHUNKED_CONTENT;
                            }
                            else if (_headerName.equalsIgnoreCase("Content-Length")) {
                                try {
                                    _contentLength = Long.parseLong(value);
                                }
                                catch (NumberFormatException t) {
                                    error(new BadRequest(400, "Invalid Content-Length: '" + value + "'\n"));
                                }

                                _endOfContent = _contentLength <= 0 ?
                                        EndOfContent.NO_CONTENT:EndOfContent.CONTENT_LENGTH;
                            }
                        }
                    }

                    // Send off the header and see if we wish to continue
                    try {
                        headerComplete(_headerName, value);
                    } catch (ParserException e) {
                        throw e;
                    } catch (Exception e) {
                        shutdown();
                        throw new ExternalExeption(e, "header submission: '" +
                            _headerName + ": " + value);
                    } finally {
                        _hstate = HeaderState.HEADER_IN_NAME;
                    }

                    break;

                case END:
                    return error(new ParsingError("Header parser reached invalid position."));
            }   // Switch
        }   // while loop

    }

    protected final boolean parseContent(ByteBuffer in) throws ParsingError, ExternalExeption {

        switch (_endOfContent) {
            case UNKNOWN_CONTENT:
                // Need Content-Length or Transfer-Encoding to signal a body for GET
                // rfc2616 Sec 4.4 for more info
                // What about custom verbs which may have a body?
                if (_methodString != "POST" || _methodString != "PUT") {
                    shutdown();
                    return true;
                }

                // We could also CONSIDER doing a BAD Request here.
                _endOfContent = EndOfContent.SELF_DEFINING_CONTENT;
                //return parseContent(in);
                throw new NotImplementedException();

            case CONTENT_LENGTH:
                return nonChunkedContent(in);

            case CHUNKED_CONTENT:
                    return chunkedContent(in);

            case SELF_DEFINING_CONTENT:

            default:
                return error(new ParsingError("not implemented: " + _endOfContent));
        }
    }

    private boolean submitPartial(ByteBuffer in, int size) {

        // Perhaps we are just right? Might be common.
        if (size == in.remaining()) {
            return submitContent(in);
        }

        final ByteBuffer b = ByteBuffer.allocate(size);

        final int old_lim = in.limit();
        in.limit(in.position() + size);
        in.mark();

        b.put(in);
        b.flip();
        in.limit(old_lim);

        if (submitContent(b)) { // Successful submission
            return true;
        }
        else {                  // need to reset things
            in.reset();
            return false;
        }
    }

    private boolean nonChunkedContent(ByteBuffer in) {

        final long remaining = _contentLength - _contentPosition;

        final int buf_size = in.remaining();

        if (buf_size >= remaining) {
            if (submitPartial(in, (int)remaining)) {
                _contentPosition += remaining;
                shutdown();
                return true;
            }
            else {
                return false;
            }
        }
        else {
            if (submitContent(in)) {
                _contentPosition += buf_size;
                return true;
            }
            else {
                return false;
            }
        }
    }

    private boolean chunkedContent(ByteBuffer in) throws ParsingError, ExternalExeption {
        while(true) {
            byte ch;
            sw: switch (_chunkState) {
                case START:
                    _chunkState = ChunkState.CHUNK_SIZE;
                    // Don't want the chunk size and extension field to be too long.
                    setLimit(256);

                case CHUNK_SIZE:
                    assert _chunkPosition == 0;

                    while (true) {

                        ch = next(in);
                        if (ch == 0) return true;

                        if (HttpTokens.isWhiteSpace(ch) || ch == HttpTokens.SEMI_COLON) {
                            _chunkState = ChunkState.CHUNK_PARAMS;
                            break;  // Break out of the while loop, and fall through to params
                        }
                        else if (ch == HttpTokens.LF) {
                            _chunkState = _chunkLength == 0 ? ChunkState.CHUNK_TRAILERS : ChunkState.CHUNK;
                            break sw;
                        }
                        else {
                            _chunkLength = 16 * _chunkLength + HttpTokens.hexCharToInt(ch);

                            if (_chunkLength > maxChunkSize) {
                                return error(new ParsingError("Chunk length too large: " + _chunkLength));
                            }
                        }
                    }

                case CHUNK_PARAMS:
                    // Don't store them, for now.
                    for(ch = next(in); ch != HttpTokens.LF; ch = next(in)) {
                        if (ch == 0) return true;
                    }

                    // Check to see if this was the last chunk
                    _chunkState = _chunkLength == 0 ? ChunkState.CHUNK_TRAILERS : ChunkState.CHUNK;
                    break;

                case CHUNK:
                    final int remaining_chunk_size =  _chunkLength - _chunkPosition;
                    final int chunk_size = in.remaining();

                    if (remaining_chunk_size <= chunk_size) {
                        if (submitPartial(in, remaining_chunk_size)) {
                            _chunkPosition = _chunkLength = 0;
                            _chunkState = ChunkState.CHUNK_LF;
                            // fall through
                        }
                        else {
                            return false;
                        }
                    }
                    else {
                        if (submitContent(in)) {
                            _chunkPosition += chunk_size;
                            return true;
                        }
                        else {
                            return false;
                        }
                    }

                case CHUNK_LF:
                    ch = next(in);

                    if (ch == 0) return true;

                    if (ch != HttpTokens.LF) {
                        return error(new ParsingError("Bad chunked encoding char: '" + (char)ch + "'"));
                    }

                    _chunkState = ChunkState.START;
                    break;


                case CHUNK_TRAILERS:    // more headers
                    assert _hstate == HeaderState.END;
                    _hstate = HeaderState.START;

                    // will determine if we are in Content or trailer mode, and set the end state
                    try {
                        return parseHeaders(in);
                    } catch (BadRequest e) {
                        throw new ParsingError("Error parsing trailers: " + e.msg());
                    } catch (ExternalExeption e) {
                        throw e;
                    } catch (ParserException e) {
                        throw new ParsingError("Received unknown error: " + e.msg());
                    }
            }
        }
    }
}
