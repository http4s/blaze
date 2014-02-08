package blaze.http_parser;

import java.nio.ByteBuffer;

import blaze.http_parser.BaseExceptions.*;


/**
 * @author Bryce Anderson
 *         Created on 1/2/14
 */
public abstract class Http1ServerParser extends BodyAndHeaderParser {

    private enum LineState {
        START,
        METHOD,
        SPACE1,
        URI,
        SPACE2,
        REQUEST_VERSION,
        END,
    }



    private final int maxRequestLineSize;


    /* ------------------------------------------------------------------- */

    private LineState _lineState = LineState.START;

    private String _methodString;
    private String _uriString;


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

    /* ------------------------------------------------------------------ */

    public final boolean requestLineComplete() {
        return _lineState == LineState.END;
    }

    /* ------------------------------------------------------------------ */

    @Override
    public void reset() {
        super.reset();
        _internalReset();
    }

    private void _internalReset() {
        _lineState = LineState.START;
    }

    /* ------------------------------------------------------------------ */

    @Override
    public void shutdownParser() {
        super.shutdownParser();
        _lineState = LineState.END;
    }

    /* ------------------------------------------------------------------ */
    // the sole Constructor

    public Http1ServerParser(int maxReqLen, int maxHeaderLength, int initialBufferSize, int maxChunkSize) {
        super(initialBufferSize, maxHeaderLength, maxChunkSize);

        this.maxRequestLineSize = maxReqLen;
        _internalReset();
    }

    public Http1ServerParser(int maxReqLen, int maxHeaderLength, int initialBufferSize) {
        this(maxReqLen, maxHeaderLength, initialBufferSize, Integer.MAX_VALUE);
    }

    public Http1ServerParser(int initialBufferSize) {
        this(2048, 40*1024, initialBufferSize);
    }

    public Http1ServerParser() { this(10*1024); }

    /* ------------------------------------------------------------------ */

    @Override
    public boolean mayHaveBody() {
        return _methodString == null || _methodString == "POST" || _methodString == "PUT";
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
                        shutdownParser();
                        throw new BadRequest("Invalid request method: '" + badmethod + "'");
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

                    _lineState = LineState.SPACE2;

                case SPACE2:
                    // Eat whitespace
                    for(ch = next(in); ch == HttpTokens.SPACE || ch == HttpTokens.TAB; ch = next(in));

                    if (ch == 0) return false;

                    if (ch != 'H') {
                        shutdownParser();
                        throw new BadRequest("Http version started with illegal character: " + 'c');
                    }

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
                    }
                    else {
                        String reason =  "Bad HTTP version: " + getString();
                        clearBuffer();
                        shutdownParser();
                        throw new BadRequest(reason);
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


}
