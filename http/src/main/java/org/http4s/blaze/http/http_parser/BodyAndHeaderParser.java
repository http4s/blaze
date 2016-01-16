package org.http4s.blaze.http.http_parser;

import java.nio.ByteBuffer;

import org.http4s.blaze.http.http_parser.BaseExceptions.BadRequest;
import org.http4s.blaze.http.http_parser.BaseExceptions.InvalidState;
import org.http4s.blaze.util.BufferTools;

/**        This HTTP/1.0 HTTP/1.1 parser is strongly inspired by the awesome Jetty parser
 *         http://www.eclipse.org/jetty/
 */
public abstract class BodyAndHeaderParser extends ParserBase {

    private static enum HeaderState {
        START,
        HEADER_IN_NAME,
        HEADER_IN_VALUE,
        HEADER_SKIP,
        END
    }

    private static enum ChunkState {
        START,
        CHUNK_SIZE,
        CHUNK_PARAMS,
        CHUNK,
        CHUNK_LF,
        CHUNK_TRAILERS,
        END
    }

    public static enum EndOfContent {
        UNKNOWN_CONTENT,
        NO_CONTENT,
        CONTENT_LENGTH,
        CHUNKED_CONTENT,
        EOF_CONTENT,
        END,
    }


    private final int headerSizeLimit;
    private final int maxChunkSize;

    private HeaderState _hstate = HeaderState.START;

    private long _contentLength;
    private long _contentPosition;

    private ChunkState _chunkState;
    private long _chunkLength;

    private String _headerName;
    private EndOfContent _endOfContent;

    /* --------------------------------------------------------------------- */

    final protected static char[] HTTP10Bytes  = "HTTP/1.0".toCharArray();
    final protected static char[] HTTP11Bytes  = "HTTP/1.1".toCharArray();

    final protected static char[] HTTPS10Bytes = "HTTPS/1.0".toCharArray();
    final protected static char[] HTTPS11Bytes = "HTTPS/1.1".toCharArray();

    /* Constructor --------------------------------------------------------- */

    protected BodyAndHeaderParser(int initialBufferSize, int headerSizeLimit, int maxChunkSize, boolean isLenient) {
        super(initialBufferSize, isLenient);
        this.headerSizeLimit = headerSizeLimit;
        this.maxChunkSize = maxChunkSize;

        _internalReset();
    }

    /**
     * This is the method called by parser when a HTTP Header name and value is found
     * @param name The name of the header
     * @param value The value of the header
     * @return True if the parser should return to its caller
     */
    protected abstract boolean headerComplete(String name, String value) throws BaseExceptions.BadRequest;

    /** determines if a body must not follow the headers */
    public abstract boolean mustNotHaveBody();

    /* Status methods ---------------------------------------------------- */

    public final boolean headersComplete() {
        return _hstate == HeaderState.END;
    }

    public final boolean contentComplete() {
        return mustNotHaveBody() ||
            _endOfContent == EndOfContent.END ||
            _endOfContent == EndOfContent.NO_CONTENT;
    }

    public final boolean isChunked() {
        return _endOfContent == EndOfContent.CHUNKED_CONTENT;
    }

    public final boolean inChunkedHeaders() {
        return _chunkState == ChunkState.CHUNK_TRAILERS;
    }

    public final boolean definedContentLength() {
        return _endOfContent == EndOfContent.CONTENT_LENGTH;
    }

    public final EndOfContent getContentType() {
        return _endOfContent;
    }

    /* ----------------------------------------------------------------- */

    @Override
    void reset() {
        super.reset();
        _internalReset();
    }

    private void _internalReset() {
        _hstate = HeaderState.START;
        _chunkState = ChunkState.START;

        _endOfContent = EndOfContent.UNKNOWN_CONTENT;

        _contentLength = 0;
        _contentPosition = 0;
        _chunkLength = 0;
    }

    @Override
    public void shutdownParser() {
        _hstate       = HeaderState.END;
        _chunkState   = ChunkState.END;
        _endOfContent = EndOfContent.END;
        super.shutdownParser();
    }

    /** Parse headers
     *
     * @param in input ByteBuffer
     * @return true if successful, false if more input is needed
     * @throws BaseExceptions.BadRequest on invalid input
     * @throws BaseExceptions.InvalidState if called when the parser is not ready to accept headers
     */
    protected final boolean parseHeaders(ByteBuffer in) throws BaseExceptions.BadRequest, BaseExceptions.InvalidState {

        headerLoop: while (true) {
            char ch;
            switch (_hstate) {
                case START:
                    _hstate = HeaderState.HEADER_IN_NAME;
                    resetLimit(headerSizeLimit);

                case HEADER_IN_NAME:
                    try {
                        for(ch = next(in, false); ch != ':' && ch != HttpTokens.LF; ch = next(in, false)) {
                            if (ch == 0) return false;
                            putChar(ch);
                        }
                    } catch (BaseExceptions.BadCharacter c) {
                        if (isLenient()) {
                            _hstate = HeaderState.HEADER_SKIP;
                            continue headerLoop;
                        }
                        else {
                            shutdownParser();
                            throw c;
                        }
                    }

                    // Must be done with headers
                    if (bufferPosition() == 0) {
                        _hstate = HeaderState.END;

                        // Finished with the whole request
                        if (_chunkState == ChunkState.CHUNK_TRAILERS) {
                            shutdownParser();
                        }
                        else if ((_endOfContent == EndOfContent.UNKNOWN_CONTENT && mustNotHaveBody())) {
                            shutdownParser();
                        }

                        // Done parsing headers
                        return true;
                    }

                    if (ch == HttpTokens.LF) {  // Valueless header
                        String name = getString();
                        clearBuffer();

                        if (headerComplete(name, "")) {
                            return true;
                        }

                        continue headerLoop;    // Still parsing Header name
                    }

                    _headerName = getString();
                    clearBuffer();
                    _hstate = HeaderState.HEADER_IN_VALUE;

                case HEADER_IN_VALUE:

                    try {
                        for(ch = next(in, true); ch != HttpTokens.LF; ch = next(in, true)) {
                            if (ch == 0) return false;
                            putChar(ch);
                        }
                    } catch (BaseExceptions.BadCharacter c) {
                        if (isLenient()) {
                            _hstate = HeaderState.HEADER_SKIP;
                            continue headerLoop;
                        }
                        else {
                            shutdownParser();
                            throw c;
                        }
                    }

                    String value;
                    try { value = getTrimmedString(); }
                    catch (BaseExceptions.BadRequest e) {
                        shutdownParser();
                        throw e;
                    }
                    clearBuffer();

                    // If we are not parsing trailer headers, look for some that are of interest to the request
                    if (_chunkState != ChunkState.CHUNK_TRAILERS) {
                        if (_headerName.equalsIgnoreCase("Transfer-Encoding")) {
                            if (value.equalsIgnoreCase("chunked")) {
                                // chunked always overrides Content-Length
                                if (_endOfContent != EndOfContent.END) {
                                    _endOfContent = EndOfContent.CHUNKED_CONTENT;
                                }
                            } else if (value.equalsIgnoreCase("identity")) {
                                // TODO: remove support for identity Transfer-Encoding.
                                // It  was removed from the specification before it was published 
                                // (althoug some traces of it remained until errata was released: 
                                // http://lists.w3.org/Archives/Public/ietf-http-wg-old/2001SepDec/0018.html
                                // if (_endOfContent == EndOfContent.UNKNOWN_CONTENT) {
                                //     _endOfContent = EndOfContent.EOF_CONTENT; 
                                // }
                            }
                            else {
                                shutdownParser();
                                // TODO: server should return 501 - https://tools.ietf.org/html/rfc7230#page-30
                                throw new BadRequest("Unknown Transfer-Encoding: " + value);
                            }
                        }
                        else if (_endOfContent != EndOfContent.CHUNKED_CONTENT && _headerName.equalsIgnoreCase("Content-Length")) {
                            try {
                                long len = Long.parseLong(value);
                                if ((_endOfContent == EndOfContent.NO_CONTENT || 
                                     _endOfContent == EndOfContent.CONTENT_LENGTH) && len != _contentLength) {
                                    // Multiple different Content-Length headers are an error
                                    // https://tools.ietf.org/html/rfc7230#page-31
                                    long oldLen = _contentLength;
                                    shutdownParser();
                                    throw new BadRequest("Duplicate Content-Length headers detected: " +
                                                         oldLen + " and " + len + "\n");
                                }
                                else if (len > 0) {
                                    _contentLength = len;
                                    _endOfContent = EndOfContent.CONTENT_LENGTH;
                                }
                                else if (len == 0) {
                                    _contentLength = len;
                                    _endOfContent = EndOfContent.NO_CONTENT;
                                }
                                else { // negative content-length
                                    shutdownParser();
                                    throw new BadRequest("Cannot have negative Content-Length: '" + len + "'\n");
                                }
                            }
                            catch (NumberFormatException t) {
                                shutdownParser();
                                throw new BadRequest("Invalid Content-Length: '" + value + "'\n");
                            }
                        }
                    }

                    // Send off the header and see if we wish to continue
                    try {
                        if (headerComplete(_headerName, value)) {
                            return true;
                        }
                    } finally {
                        _hstate = HeaderState.HEADER_IN_NAME;
                    }

                    break;

                case HEADER_SKIP:
                    // discard the header
                    clearBuffer();
                    byte b;
                    for(b = nextByte(in); b != HttpTokens.LF && b != 0; b = nextByte(in));
                    if (b == 0) return false;
                    _hstate = HeaderState.HEADER_IN_NAME;
                    continue headerLoop;

                case END:
                    shutdownParser();
                    throw new InvalidState("Header parser reached invalid position.");
            }   // Switch
        }   // while loop
    }

    /** Parses the buffer into body content
     * @param in ByteBuffer to parse
     * @return a ByteBuffer with the parsed body content. Buffer in may not be depleted. If more data is
     *         needed, null is returned. In the case of content complete, an empty ByteBuffer is returned.
     * @throws BaseExceptions.ParserException
     */
    protected final ByteBuffer parseContent(ByteBuffer in) throws BaseExceptions.ParserException {
        if (contentComplete()) {
            throw new BaseExceptions.InvalidState("content already complete: " + _endOfContent);
        } else {
            switch (_endOfContent) {
            case UNKNOWN_CONTENT:
                // This makes sense only for response parsing. Requests must always have
                // either Content-Length or Transfer-Encoding

                _endOfContent = EndOfContent.EOF_CONTENT;
                _contentLength = Long.MAX_VALUE;    // Its up to the user to limit a body size
                return parseContent(in);

            case CONTENT_LENGTH:
            case EOF_CONTENT:
                return nonChunkedContent(in);

            case CHUNKED_CONTENT:
                return chunkedContent(in);

            default:
                throw new BaseExceptions.InvalidState("not implemented: " + _endOfContent);
            }
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
            if (buf_size > 0) {
                _contentPosition += buf_size;
                return submitBuffer(in);
            }
            else return null;
        }
    }

    private ByteBuffer chunkedContent(ByteBuffer in) throws BaseExceptions.BadRequest, BaseExceptions.InvalidState {
        while(true) {
            char ch;
            sw: switch (_chunkState) {
                case START:
                    _chunkState = ChunkState.CHUNK_SIZE;
                    // Don't want the chunk size and extension field to be too long.
                    resetLimit(256);

                case CHUNK_SIZE:
                    assert _contentPosition == 0;

                    while (true) {

                        ch = next(in, false);
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
                                shutdownParser();
                                throw new BadRequest("Chunk length too large: " + _chunkLength);
                            }
                        }
                    }

                case CHUNK_PARAMS:
                    // Don't store them, for now.
                    for(ch = next(in, false); ch != HttpTokens.LF; ch = next(in, false)) {
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
                    final long remaining_chunk_size =  _chunkLength - _contentPosition;
                    final int buff_size = in.remaining();

                    if (remaining_chunk_size <= buff_size) {
                        ByteBuffer result = submitPartialBuffer(in, (int)remaining_chunk_size);
                        _contentPosition = _chunkLength = 0;
                        _chunkState = ChunkState.CHUNK_LF;
                        return result;
                    }
                    else {
                        if (buff_size > 0) {
                            _contentPosition += buff_size;
                            return submitBuffer(in);
                        }
                        else return null;
                    }

                case CHUNK_LF:
                    ch = next(in, false);
                    if (ch == 0) return null;

                    if (ch != HttpTokens.LF) {
                        shutdownParser();
                        throw new BadRequest("Bad chunked encoding char: '" + ch + "'");
                    }

                    _chunkState = ChunkState.START;
                    break;


                case CHUNK_TRAILERS:    // more headers
                    if (parseHeaders(in)) {
                        // headers complete
                        return BufferTools.emptyBuffer();
                    }
                    else {
                        return null;
                    }
            }
        }
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

}
