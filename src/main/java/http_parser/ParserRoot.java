package http_parser;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import http_parser.BaseExceptions.*;
import http_parser.HttpTokens.EndOfContent;



/**
 * @author Bryce Anderson
 *         Created on 1/2/14
 */
public class ParserRoot {

    public static boolean isDigit(byte ch) {
        return HttpTokens.NINE >= ch && ch >= HttpTokens.ZERO;
    }

    public static boolean isWhiteSpace(byte ch) {
        return ch == HttpTokens.SPACE || ch == HttpTokens.TAB;
    }

    private static byte[] HTTP10Bytes = "HTTP/1.0".getBytes(Charset.forName("US-ASCII"));
    private static byte[] HTTPS10Bytes = "HTTPS/1.0".getBytes(Charset.forName("US-ASCII"));

    private static byte[] HTTP11Bytes = "HTTP/1.1".getBytes(Charset.forName("US-ASCII"));
    private static byte[] HTTPS11Bytes = "HTTPS/1.1".getBytes(Charset.forName("US-ASCII"));

    // States
    public enum State {
        METHOD,
        URI,
        SPACE1,
        REQUEST_VERSION,
//        RESPONSE_VERSION,
//        STATUS,
        SPACE2,
//        REASON,
//        HEADER,
        HEADER_IN_NAME,
        HEADER_SPACE,
        HEADER_IN_VALUE,
        CONTENT,
        END,
    }

    public enum ChunkState {
        CHUNKED_CONTENT,
        CHUNK_SIZE,
        CHUNK_PARAMS,
        CHUNK,
        CHUNK_EOF,
    }

    private final int requestSizeLimit;
    private final int headerSizeLimit;
    private final int initialBufferSize;
    private final RequestHandler<ByteBuffer> handler;

    /* ------------------------------------------------------------------- */
    private volatile State _state=State.METHOD;
    private String _methodString;
    private String _uriString;
    private boolean _hostRequired;
    private String _host = null;
    private String _headerName;
    private EndOfContent _endOfContent;
    private long _contentLength;
    private long _contentPosition;
    private int _chunkLength;
    private int _chunkPosition;
//    private boolean _headResponse;
    private boolean _cr;
    private ByteBuffer _contentChunk;
    private int remainingLimit;


    /* ------------------------------------------------------------------ */

    public State getState() { return _state; }

    public EndOfContent getContentType() { return _endOfContent; }

    public void reset() {
        clearBuffer();
        setState(State.METHOD);
        setLimit(requestSizeLimit);

        _internalBuffer = new byte[initialBufferSize];
        _endOfContent = EndOfContent.UNKNOWN_CONTENT;
        _contentLength = 0;
        _chunkLength = 0;
        _chunkPosition = 0;
        _hostRequired = true;
    }

    /* ------------------------------------------------------------------ */
    // Methods for managing the internal buffer

    private int _bufferPosition = 0;
    private int _bufferLen = 0;
    private byte[] _internalBuffer;

    private void ensureRoom(int size) {
        if (_bufferPosition + size >= _bufferLen) {
            // Resize the internal array
            int nextsize = Math.max(_bufferLen*2, _bufferLen + 2*size);
            this._bufferLen = nextsize;
            byte[] next = new byte[nextsize];
            System.arraycopy(_internalBuffer, 0, next, 0, _bufferPosition);
            _internalBuffer = next;
        }
    }

    private void putByte(byte c) {
        ensureRoom(1);
        _internalBuffer[_bufferPosition++] = c;
    }

    private int bufferPosition() { return _bufferPosition; }

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
            if (!isWhiteSpace(ch) && ch != '"') {
                break;
            }
            start++;
        }

        // Look for end
        while(end > start) {
            byte ch = getByte(end);
            if (!isWhiteSpace(ch) && ch != '"') {
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

    private void putChar(char c) { putByte((byte)c); }

    private void putArray(byte[] arr, int pos, int len) {
        assert !(pos + len > arr.length);

        ensureRoom(len);

        System.arraycopy(arr, pos, _internalBuffer, _bufferPosition, len);
        _bufferPosition += len;
    }

    private ByteBuffer getBuffer() {
        ByteBuffer out = ByteBuffer.wrap(_internalBuffer, 0, _bufferPosition);
        _internalBuffer = new byte[_bufferLen];
        _bufferPosition = 0;
        return out;
    }



    /* ------------------------------------------------------------------ */
    // Constructor

    public ParserRoot(RequestHandler<ByteBuffer> handler, int maxReqLen, int maxHeaderLength, int initialBufferSize) {
        this.handler = handler;
        this.requestSizeLimit = maxReqLen;
        this.headerSizeLimit = maxHeaderLength;
        this.initialBufferSize = initialBufferSize;

        reset();
    }

    /* ------------------------------------------------------------------ */

    private void setLimit(int limit) {
        remainingLimit = limit;
    }

    private void readLine(ByteBuffer in) throws BaseParseExcept {
        byte ch = next(in);
        while(ch != HttpTokens.LF) {
            putByte(ch);
            ch = next(in);
        }
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

    private void error(BaseParseExcept e) throws BaseParseExcept {

        if (e instanceof BadRequest) {
            BadRequest badreq = (BadRequest)e;
            handler.badMessage(badreq.getCode(), badreq.msg());
        }

        setState(State.END);
        throw e;
    }


    private void checkMethod(String method) throws BaseParseExcept {
//        if (method.equalsIgnoreCase("GET")) {
//            _endOfContent = EndOfContent.NO_CONTENT;
//        }
    }

    protected final void setState(State state) {
        _state = state;
    }

    protected final boolean parseRequestLine(ByteBuffer in) throws BaseParseExcept {
        lineLoop: while(true) {
            byte ch = next(in);
            switch (_state) {
                case METHOD:
                    while(!(ch == HttpTokens.SPACE || ch == HttpTokens.TAB)) {
                        putByte(ch);
                        ch = next(in);
                    }

                    _methodString = getString();
                    clearBuffer();

                    // Determine if this method can have a body
                    checkMethod(_methodString);

                    setState(State.SPACE1);
                    break;

                case SPACE1:    // Eat whitespace
                    while(ch == HttpTokens.SPACE || ch == HttpTokens.TAB) {
                        ch = next(in);
                    }
                    putByte(ch);
                    setState(State.URI);
                    break;


                case URI:
                    while(!(ch == HttpTokens.SPACE || ch == HttpTokens.TAB)) {
                        putByte(ch);
                        ch = next(in);
                    }
                    _uriString = getString();
                    clearBuffer();
                    setState(State.SPACE2);
                    break;

                case SPACE2:
                    while(ch == HttpTokens.SPACE || ch == HttpTokens.TAB) {
                        ch = next(in);
                    }

                    if (ch != 'H') {
                        error(new ParsingError("Http version started with illegal character: " + 'c'));
                    }
                    putByte(ch);
                    setState(State.REQUEST_VERSION);
                    break;

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

                    String scheme = getString(bufferPosition() - 3);
                    clearBuffer();

                    // We are through parsing the request line
                    setState(State.HEADER_IN_NAME);
                    setLimit(headerSizeLimit); // Only need one, then it gets set in
                    return handler.startRequest(_methodString, _uriString, scheme, _majorversion, _minorversion);
            }    // switch
        }        // while loop
    }

    protected final boolean parseHeaders(ByteBuffer in) throws BaseParseExcept {
        headerLoop: while(true) {
            byte ch = next(in);
            switch (_state) {
                case HEADER_IN_NAME:
                    while(ch != ':' && ch != HttpTokens.LF) {
                        putByte(ch);
                        ch = next(in);
                    }

                    // Must be done with headers
                    if (bufferPosition() == 0) {
                        if (_hostRequired) {
                            error(new BadRequest(400, "Missing host header"));
                        }

                        setState(State.CONTENT);
                        return true;
                    }

                    if (ch == HttpTokens.LF) {  // Valueless header
                        String name = getString();
                        setLimit(headerSizeLimit);
                        clearBuffer();
                        handler.parsedHeader(name, "");

                        continue headerLoop;    // Still parsing Header name
                    }

                    _headerName = getString();
                    clearBuffer();
                    setState(State.HEADER_SPACE);
                    break;

                case HEADER_SPACE:
                    while(ch == HttpTokens.SPACE || ch == HttpTokens.TAB) {
                        ch = next(in);
                    }
                    putByte(ch);
                    setState(State.HEADER_IN_VALUE);
                    break;


                case HEADER_IN_VALUE:
                    while(ch != HttpTokens.LF) {
                        putByte(ch);
                        ch = next(in);
                    }

                    String value = getTrimmedString();
                    clearBuffer();

                    // Check for host if it is still needed
                    if (_hostRequired && _headerName.equalsIgnoreCase("Host")) {
                        _hostRequired = false;  // Don't search for the host header anymore
                        _host = value;
                    }

                    // Check for content type if its still not determined
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
                                    error(new BadRequest(400, "Invalid Content-Length: " + value));
                                }

                                _endOfContent = _chunkLength <= 0 ? EndOfContent.NO_CONTENT:EndOfContent.CONTENT_LENGTH;
                            }

                    }

                    // Send off the header and see if we wish to continue
                    if (!handler.parsedHeader(_headerName, value)) {
                        setState(State.END);
                        return false;
                    }

                    setLimit(headerSizeLimit);
                    setState(State.HEADER_IN_NAME);
                    break;

            }   // Switch
        }   // while loop

    }

    private boolean parseContent(ByteBuffer in) throws BaseParseExcept {
        switch (_endOfContent) {
            case UNKNOWN_CONTENT:
                return parseContent(-1);
            case CONTENT_LENGTH:
                return parseContent(_contentLength);

            case CHUNKED_CONTENT: return parseChunkContent(in);

            default:
                error(new ParsingError("not implemented: " + _endOfContent));
                return false;
        }
    }

    private boolean parseContent(long remaining) throws BaseParseExcept {

        return false;
    }

    private boolean parseChunkContent(ByteBuffer in) throws BaseParseExcept {

        return false;
    }



    /* ------------------------------------------------------------------ */
    // Interfaces that will be used to interact with the outside world

    /** copied from Eclipse Jetty and modified for my purposes
     *
     * @param <T> type of content
     */
    public interface HttpHandler<T>
    {
        public boolean content(T item);

        public boolean headerComplete();

        public boolean messageComplete();

        /**
         * This is the method called by parser when a HTTP Header name and value is found
         * @param name The name of the header
         * @param value The value of the header
         * @return True if the parser should return to its caller
         */
        public boolean parsedHeader(String name, String value);

        /* ------------------------------------------------------------ */
        /** Called to signal that an EOF was received unexpectedly
         * during the parsing of a HTTP message
         */
        public void earlyEOF();

        /* ------------------------------------------------------------ */
        /** Called to signal that a bad HTTP message has been received.
         * @param status The bad status to send
         * @param reason The textual reason for badness
         */
        public void badMessage(int status, String reason);

        /* ------------------------------------------------------------ */
        /** @return the size in bytes of the per parser header cache
         */
        public int getHeaderCacheSize();
    }

    public interface RequestHandler<T> extends HttpHandler<T>
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         * @param methodString The method as a string
         * @param uri The raw bytes of the URI.  These are copied into a ByteBuffer that will not be changed until this parser is reset and reused.
         * @param majorversion major version
         * @param minorversion minor version
         * @return true if handling parsing should return.
         */
        public abstract boolean startRequest(String methodString, String uri, String scheme, int majorversion, int minorversion);

        /**
         * This is the method called by the parser after it has parsed the host header (and checked it's format). This is
         * called after the {@link HttpHandler#parsedHeader(String, String)} methods and before
         * HttpHandler#headerComplete();
         */
        public abstract boolean parsedHeader(String name, String value);
    }

}
