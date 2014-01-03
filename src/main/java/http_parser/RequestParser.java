package http_parser;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import http_parser.BaseExceptions.*;
import http_parser.HttpTokens.EndOfContent;



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
        CHUNK_EOFLF,
        CHUNK_TRAILERS,
        END
    }

    private final int requestSizeLimit;
    private final int headerSizeLimit;

    /* ------------------------------------------------------------------- */
    private volatile State _state=State.START;
    private volatile HeaderState _hstate = HeaderState.START;

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
    private int remainingLimit;


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

    public abstract boolean headersComplete();

    public abstract void requestComplete();

    /**
     * This is the method called by parser when a HTTP Header name and value is found
     * @param name The name of the header
     * @param value The value of the header
     * @return True if the parser should return to its caller
     */
    public abstract boolean headerComplete(String name, String value);

        /* ------------------------------------------------------------ */
    /** Called to signal that an EOF was received unexpectedly
     * during the parsing of a HTTP message
     */
    public abstract void earlyEOF();

        /* ------------------------------------------------------------ */
    /** Called to signal that a bad HTTP message has been received.
     * @param status The bad status to send
     * @param reason The textual reason for badness
     */
    public abstract void badMessage(int status, String reason);


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
               _hstate != HeaderState.START &&
               _hstate != HeaderState.END;
    }

    public final boolean inContent() {
        return _state == State.CONTENT;
    }

    public final boolean inDefinedContent() {
        return inContent() &&
                _endOfContent == EndOfContent.CONTENT_LENGTH;
    }

    public final boolean inChunked() {
        return inContent() &&
                _endOfContent == EndOfContent.CHUNKED_CONTENT;
    }

    public boolean inChunkedHeaders() {
        return _state == State.CONTENT &&
               _hstate != HeaderState.START &&
               _hstate != HeaderState.END;
    }

    public EndOfContent getContentType() {
        return _endOfContent;
    }

    public void reset() {
        clearBuffer();

        _state = State.START;
        _hstate = HeaderState.START;
        _chunkState = ChunkState.START;

        setLimit(requestSizeLimit);

        _endOfContent = EndOfContent.UNKNOWN_CONTENT;
        _contentLength = 0;
        _contentPosition = 0;
        _chunkLength = 0;
        _chunkPosition = 0;
        _hostRequired = true;
    }

    /* ------------------------------------------------------------------ */

    protected void cleanup() {
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

    private void ensureRoom(int size) {
        if (_bufferPosition + size >= _bufferLen) {
            makeRoom(size);
        }
    }

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

    private byte getByte(int index) {
        assert index > -1 && index < _bufferLen;
        return _internalBuffer[index];
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
        String str = new String(_internalBuffer, start, end);
        return str;
    }

    private String getTrimmedString() throws BaseParseExcept {
        int start = 0;
        int end = _bufferPosition - 1;  // Position is of next write

        // Look for start
        while(start < _bufferPosition) {
            byte ch = getByte(start);
            if (!HttpTokens.isWhiteSpace(ch) && ch != '"') {
                break;
            }
            start++;
        }

        // Look for end
        while(end > start) {
            byte ch = getByte(end);
            if (!HttpTokens.isWhiteSpace(ch) && ch != '"') {
                break;
            }
            end--;
        }

        if (start == _bufferPosition || end <= start) {
            error(new ParsingError("String not quoted correctly: '" + getString() + "'"));
        }

        String str = new String(_internalBuffer, start, end + 1);
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

//    private void putChar(char c) { putByte((byte)c); }

    private void putArray(byte[] arr, int pos, int len) {
        assert !(pos + len > arr.length);

        ensureRoom(len);

        System.arraycopy(arr, pos, _internalBuffer, _bufferPosition, len);
        _bufferPosition += len;
    }

//    private ByteBuffer getBuffer() {
//        ByteBuffer out = ByteBuffer.wrap(_internalBuffer, 0, _bufferPosition);
//        _internalBuffer = new byte[_bufferLen];
//        _bufferPosition = 0;
//        return out;
//    }



    /* ------------------------------------------------------------------ */
    // the sole Constructor

    public RequestParser(int maxReqLen, int maxHeaderLength, int initialBufferSize) {
        this.requestSizeLimit = maxReqLen;
        this.headerSizeLimit = maxHeaderLength;

        _internalBuffer = new byte[initialBufferSize];
        reset();
    }

    /* ------------------------------------------------------------------ */

    private void setLimit(int limit) {
        remainingLimit = limit;
    }

    // Removes CRs but returns LFs
    private byte next(ByteBuffer buffer) throws BaseParseExcept {
        if (!buffer.hasRemaining()) throw BaseExceptions.needsInput;

        if (remainingLimit == 0) {
            throw new ParsingError("Request length limit exceeded: " + this.headerSizeLimit);
        }

        final byte ch = buffer.get();
        remainingLimit--;

        if (_cr) {
            if (ch != HttpTokens.LF) {
                throw new ParsingError("Invalid sequence: LF didn't follow CR: " + ch);
            }

            _cr = false;
            return ch;
        }

        if (ch == HttpTokens.CR) {
            if (!buffer.hasRemaining()) {
                _cr = true;
                throw BaseExceptions.needsInput;
            }

            final byte lf = buffer.get();
            if (lf != HttpTokens.LF) {
                throw new ParsingError("Invalid sequence: LF didn't follow CR: " + lf);
            }

            return lf;
        }

        return ch;
    }

    /* ------------------------------------------------------------------ */

    private boolean error(BaseParseExcept e) throws BaseParseExcept {

        if (e instanceof BadRequest) {
            BadRequest badreq = (BadRequest)e;
            badMessage(badreq.getCode(), badreq.msg());
        }

        setState(State.END);
        throw e;
    }


//    private void checkMethod(String method) throws BaseParseExcept {
//        if (method.equalsIgnoreCase("GET")) {
//            _endOfContent = EndOfContent.NO_CONTENT;
//        }
//    }

    protected final void setState(State state) {
        _state = state;
    }

    protected final boolean parseRequestLine(ByteBuffer in) throws BaseParseExcept {
        lineLoop: for(byte ch = next(in);; ch = next(in)) {
            switch (_state) {
                case START:
                    _state = State.METHOD;

                case METHOD:
                    while(!(ch == HttpTokens.SPACE || ch == HttpTokens.TAB)) {
                        putByte(ch);
                        ch = next(in);
                    }

                    _methodString = getString();
                    clearBuffer();

                    // Determine if this method can have a body
//                    checkMethod(_methodString);

                    setState(State.SPACE1);
                    continue lineLoop;

                case SPACE1:    // Eat whitespace
                    while(ch == HttpTokens.SPACE || ch == HttpTokens.TAB) {
                        ch = next(in);
                    }
                    putByte(ch);
                    setState(State.URI);
                    continue lineLoop;


                case URI:
                    while(!(ch == HttpTokens.SPACE || ch == HttpTokens.TAB)) {
                        putByte(ch);
                        ch = next(in);
                    }
                    _uriString = getString();
                    clearBuffer();
                    setState(State.SPACE2);
                    continue lineLoop;

                case SPACE2:
                    while(ch == HttpTokens.SPACE || ch == HttpTokens.TAB) {
                        ch = next(in);
                    }

                    if (ch != 'H') {
                        error(new ParsingError("Http version started with illegal character: " + 'c'));
                    }
                    putByte(ch);
                    setState(State.REQUEST_VERSION);
                    continue lineLoop;

                case REQUEST_VERSION:
                    while(ch != HttpTokens.LF) {
                        putByte(ch);
                        ch = next(in);
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
                    setLimit(headerSizeLimit); // Only need one, then it gets set in
                    return startRequest(_methodString, _uriString, scheme, _majorversion, _minorversion);
            }    // switch
        }        // while loop
    }

    protected final boolean parseHeaders(ByteBuffer in) throws BaseParseExcept {
        headerLoop: for(byte ch = next(in);; ch = next(in)) {
            switch (_hstate) {
                case START:
                    _hstate = HeaderState.HEADER_IN_NAME;

                case HEADER_IN_NAME:
                    while(ch != ':' && ch != HttpTokens.LF) {
                        putByte(ch);
                        ch = next(in);
                    }

                    // Must be done with headers
                    if (bufferPosition() == 0) {

                        if (_hostRequired) {
                            // If we didn't get our host header, we have a problem.
                            error(new BadRequest(400, "Missing host header"));
                        }

                        // Notify the handler we are finished with this batch of headers
                        if (!headersComplete()) return false;

                        _hstate = HeaderState.END;

                        // Finished with the whole request
                        if (_chunkState == ChunkState.CHUNK_TRAILERS) {
                            cleanup();
                            return true;
                        }
                        else {    // now doing the body
                            setState(State.CONTENT);
                            return true;
                        }
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
                    break;

                case HEADER_SPACE:
                    while(ch == HttpTokens.SPACE || ch == HttpTokens.TAB) {
                        ch = next(in);
                    }
                    putByte(ch);
                    _hstate = HeaderState.HEADER_IN_VALUE;
                    break;


                case HEADER_IN_VALUE:
                    while(ch != HttpTokens.LF) {
                        putByte(ch);
                        ch = next(in);
                    }

                    String value = getTrimmedString();
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
                    if (!headerComplete(_headerName, value)) {
                        setState(State.END);
                        return false;
                    }

                    setLimit(headerSizeLimit);
                    _hstate = HeaderState.HEADER_IN_NAME;
                    break;
            }   // Switch
        }   // while loop

    }

    protected final boolean parseContent(ByteBuffer in) throws BaseParseExcept {

        switch (_endOfContent) {
            case UNKNOWN_CONTENT:
                // Need Content-Length or Transfer-Encoding to signal a body for GET
                // rfc2616 Sec 4.4 for more info

                // What about custom verbs which may have a body?
                if (_methodString != "POST" || _methodString != "PUT") {
                    cleanup();
                    return true;
                }

                // We could also CONSIDER doing a BAD Request here.
                _endOfContent = EndOfContent.SELF_DEFINING_CONTENT;
                return parseContent(in);

            case CONTENT_LENGTH:
                return nonChunkedContent(in);

            case CHUNKED_CONTENT:
                return chunkedContent(in);

            case SELF_DEFINING_CONTENT:
                // We have unknown length, so pass it to the handler which will tell us when to stop
                return submitContent(in);

            default:
                return error(new ParsingError("not implemented: " + _endOfContent));
        }
    }

    private boolean submitPartial(ByteBuffer in, int size) {

        // Perhaps we are just right? Might be common.
        if (size == in.remaining()) {
            return submitContent(in);
        }

        ByteBuffer b = ByteBuffer.allocate(size);

        final int old_lim = in.limit();
        in.limit(in.position() + size);
        in.mark();

        b.put(in);

        if (submitContent(b)) { // Successful submission
            in.limit(old_lim);
            return true;
        }
        else {                  // need to reset things
            in.reset();
            in.limit(old_lim);
            return false;
        }
    }

    private boolean nonChunkedContent(ByteBuffer in) throws BaseParseExcept {

        final long remaining = _contentLength - _contentPosition;

        final int buf_size = in.remaining();

        if (buf_size >= remaining) {
            if (submitPartial(in, (int)remaining)) {
                _contentPosition += remaining;
                cleanup();
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



    private boolean chunkedContent(ByteBuffer in) throws BaseParseExcept {
        while(true) {
            byte ch;
            sw: switch (_chunkState) {
                case START:
                    _chunkState = ChunkState.CHUNK_SIZE;

                case CHUNK_SIZE:
                   assert _chunkPosition == 0;

                    while (true) {
                        ch = next(in);
                        if (HttpTokens.isWhiteSpace(ch)) {
                            _chunkState = ChunkState.CHUNK_PARAMS;
                            break;  // Break out of the while loop, and fall through to params
                        }
                        else if (ch == HttpTokens.LF) {
                            _chunkState = _contentLength == 0? ChunkState.CHUNK_EOFLF : ChunkState.CHUNK;
                            break sw;
                        }
                        else {
                            _chunkLength = 16 * _chunkLength + HttpTokens.byteToHex(ch);
                        }
                    }

                case CHUNK_PARAMS:
                    // Don't store them, for now.
                    for(ch = next(in); ch != HttpTokens.LF; ch = next(in));

                    // Check to see if this was the last chunk
                    _chunkState = _chunkLength == 0 ? ChunkState.CHUNK_EOFLF: ChunkState.CHUNK;
                    break;

                case CHUNK:
                    final int remaining_chunk_size =  _chunkLength - _chunkPosition;
                    final int chunk_size = in.remaining();

                    if (remaining_chunk_size <= chunk_size) {
                        if (submitPartial(in, remaining_chunk_size)) {
                            _chunkPosition += remaining_chunk_size;
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
                case CHUNK_EOFLF:
                    ch = next(in);
                    if (ch != HttpTokens.LF) {
                        error(new BadRequest(400, "Bad chunked encoding"));
                        // Wont reach here
                        return false;
                    }
                    if (_chunkState == ChunkState.CHUNK_LF) {
                        _chunkState = ChunkState.CHUNK_SIZE;
                        break;
                    }
                    else {              // _chunkState == CHUNK_EOFLF
                    // Its trailer time
                        assert _hstate == HeaderState.END;

                        _hstate = HeaderState.HEADER_IN_NAME;
                        _chunkState = ChunkState.CHUNK_TRAILERS;
                    }

                case CHUNK_TRAILERS:    // more headers
                    // will determine if we are in Content mode, and set the end state
                    return parseHeaders(in);
            }
        }
    }
}
