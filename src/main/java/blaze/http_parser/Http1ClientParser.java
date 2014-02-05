package blaze.http_parser;

import java.nio.ByteBuffer;

/**
 * @author Bryce Anderson
 *         Created on 2/4/14
 */
public abstract class Http1ClientParser extends Http1Parser {

    Http1ClientParser(int maxRequestLineSize, int maxHeaderLength, int initialBufferSize, int maxChunkSize) {
        super(0, maxHeaderLength, initialBufferSize, maxChunkSize);
        this.maxRequestLineSize = maxRequestLineSize;
    }

    Http1ClientParser(int initialBufferSize) {
        this(2048, 40*1024, initialBufferSize, Integer.MAX_VALUE);
    }

    Http1ClientParser() { this(10*1024); }

    // Lets us call the shutdown hooks
    private class ParserBadResponse extends BaseExceptions.BadResponse {
        private ParserBadResponse(String msg) {
            super(msg);
            shutdownParser();
        }
    }

    // Status-Line = HTTP-Version SP Status-Code SP Reason-Phrase CRLF

    private enum LineState {
        START,
        VERSION,
        SPACE1,
        STATUS_CODE,
        SPACE2,
        REASON,
        END,
    }

    private final int maxRequestLineSize;

    private LineState _requestLineState = LineState.START;

    private String _lineScheme = null;
    private int _majorVersion = 0;
    private int _minorVersion = 0;
    private int _statusCode = 0;

    public abstract void submitResponseLine(int code, String reason, String scheme, int majorversion, int minorversion);

    public final boolean responseLineComplete() {
        return _requestLineState == LineState.END;
    }

    @Override
    protected void shutdownParser() {
        super.shutdownParser();    //To change body of overridden methods use File | Settings | File Templates.
        _requestLineState = LineState.END;
    }

    @Override
    public void reset() {
        super.reset();
        _requestLineState = LineState.START;
        _lineScheme = null;
        _majorVersion = 0;
        _minorVersion = 0;
        _statusCode = 0;
    }

    /** parses the request line. Returns true if completed successfully, false if needs input */
    protected final boolean parseResponseLine(ByteBuffer in) throws BaseExceptions.InvalidState, BaseExceptions.BadResponse {
        try {
            lineLoop: while(true) {
                byte ch;
                switch (_requestLineState) {
                    case START:
                        _requestLineState = LineState.VERSION;
                        resetLimit(maxRequestLineSize);

                    case VERSION:
                        for(ch = next(in); ch != HttpTokens.SPACE && ch != HttpTokens.TAB; ch = next(in)) {
                            if (ch == 0) return false;
                            putByte(ch);
                        }

                        _majorVersion = 1;
                        _minorVersion = 1;

                        if (arrayMatches(HTTP11Bytes) || arrayMatches(HTTPS11Bytes)) {
                            // NOOP, already set to this
//                        _majorversion = 1;
//                        _minorversion = 1;
                        }
                        else if (arrayMatches(HTTP10Bytes) || arrayMatches(HTTPS10Bytes)) {
                            _minorVersion = 0;
                        }
                        else {
                            String reason =  "Bad HTTP version: " + getString();
                            clearBuffer();
                            throw new ParserBadResponse(reason);
                        }

                        _lineScheme = getString(bufferPosition() - 4);
                        clearBuffer();

                        // We are through parsing the request line
                        _requestLineState = LineState.SPACE1;

                    case SPACE1:
                        // Eat whitespace
                        for(ch = next(in); ch == HttpTokens.SPACE || ch == HttpTokens.TAB; ch = next(in));

                        if (ch == 0) return false;

                        if (!HttpTokens.isDigit(ch)) {
                            throw new ParserBadResponse("Received invalid token when needed code: '" + (char)ch + "'");
                        }
                        _statusCode = 10*_statusCode + (ch - HttpTokens.ZERO);
                        _requestLineState = LineState.STATUS_CODE;

                    case STATUS_CODE:
                        for(ch = next(in); HttpTokens.isDigit(ch); ch = next(in)) {
                            if (ch == 0) return false;
                            _statusCode = 10*_statusCode + (ch - HttpTokens.ZERO);
                        }

                        if (ch == 0) return false;  // Need more data
                        if (!HttpTokens.isWhiteSpace(ch)) {
                            throw new ParserBadResponse("Invalid request: Expected SPACE but found '" + (char)ch + "'");
                        }
                        if (_statusCode < 100 || _statusCode >= 600) {
                            throw new ParserBadResponse("Invalid status code '" +
                                    _statusCode + "'. Must be between 100 and 599");
                        }

                        _requestLineState = LineState.SPACE2;

                    case SPACE2:
                        // Eat whitespace
                        for(ch = next(in); ch == HttpTokens.SPACE || ch == HttpTokens.TAB; ch = next(in));

                        if (ch == 0) return false;

                        if (ch == HttpTokens.LF) throw new ParserBadResponse("Response lacks status Reason");

                        putByte(ch);
                        _requestLineState = LineState.REASON;

                    case REASON:
                        for(ch = next(in); ch != HttpTokens.LF; ch = next(in)) {
                            if (ch == 0) return false;
                            putByte(ch);
                        }



                        String reason = getTrimmedString();
                        clearBuffer();

                        // We are through parsing the request line
                        _requestLineState = LineState.END;
                        submitResponseLine(_statusCode, reason, _lineScheme, _majorVersion, _minorVersion);
                        return true;

                    default:
                        throw new BaseExceptions.InvalidState("Attempted to parse Response line when already complete." +
                                "LineState: '" + _requestLineState + "'");
                }    // switch
            }        // while loop
        } catch (BaseExceptions.BadRequest ex) {
            throw new ParserBadResponse(ex.msg());
        }
    }

    @Override
    public final void submitRequestLine(String methodString,
                                  String uri,
                                  String scheme,
                                  int majorversion,
                                  int minorversion) { /* NOOP */ }
}
