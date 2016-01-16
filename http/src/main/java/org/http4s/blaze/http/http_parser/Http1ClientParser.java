package org.http4s.blaze.http.http_parser;

import java.nio.ByteBuffer;
import org.http4s.blaze.http.http_parser.BaseExceptions.*;

public abstract class Http1ClientParser extends BodyAndHeaderParser {

    public Http1ClientParser(int maxRequestLineSize, int maxHeaderLength, int initialBufferSize, int maxChunkSize,
                             boolean isLenient) {
        super(initialBufferSize, maxHeaderLength, maxChunkSize, isLenient);
        this.maxRequestLineSize = maxRequestLineSize;

        _internalReset();
    }

    public Http1ClientParser(int initialBufferSize, boolean isLenient) {
        this(2048, 40*1024, initialBufferSize, Integer.MAX_VALUE, isLenient);
    }

    public Http1ClientParser(boolean isLenient) {
        this(10*1024, isLenient);
    }

    public Http1ClientParser() {
        this(false);
    }

    // Status-Line = HTTP-Version SP Status-Code SP Reason-Phrase CRLF
    private enum RequestLineState {
        START,
        VERSION,
        SPACE1,
        STATUS_CODE,
        SPACE2,
        REASON,
        END,
    }

    private final int maxRequestLineSize;

    private RequestLineState _requestLineState = RequestLineState.START;
    private String _lineScheme = null;
    private int _majorVersion = 0;
    private int _minorVersion = 0;
    private int _statusCode = 0;

    final public boolean isStartState() {
        return _requestLineState == RequestLineState.START;
    }

    protected abstract void submitResponseLine(int code, String reason, String scheme, int majorversion, int minorversion);

    public final boolean responseLineComplete() {
        return _requestLineState == RequestLineState.END;
    }

    @Override
    public void shutdownParser() {
        super.shutdownParser();    //To change body of overridden methods use File | Settings | File Templates.
        _requestLineState = RequestLineState.END;
    }

    @Override
    public void reset() {
        super.reset();
        _internalReset();
    }

    private void _internalReset() {
        _requestLineState = RequestLineState.START;
        _lineScheme = null;
        _majorVersion = 0;
        _minorVersion = 0;
        _statusCode = 0;
    }

    @Override
    public boolean mustNotHaveBody() {
        return _statusCode < 200 ||
          _statusCode == 204 ||
          _statusCode == 304; // TODO: or request was HEAD
    }

    /** parses the request line. Returns true if completed successfully, false if needs input */
    protected final boolean parseResponseLine(ByteBuffer in) throws BaseExceptions.InvalidState, BaseExceptions.BadResponse {
        try {
            lineLoop: while(true) {
                char ch;
                switch (_requestLineState) {
                    case START:
                        _requestLineState = RequestLineState.VERSION;
                        resetLimit(maxRequestLineSize);

                    case VERSION:
                        for(ch = next(in, false); ch != HttpTokens.SPACE && ch != HttpTokens.TAB; ch = next(in, false)) {
                            if (ch == 0) return false;
                            putChar(ch);
                        }

                        _majorVersion = 1;
                        _minorVersion = 1;

                        if (arrayMatches(HTTP11Bytes) || arrayMatches(BodyAndHeaderParser.HTTPS11Bytes)) {
                            _majorVersion = 1;
                            _minorVersion = 1;
                        }
                        else if (arrayMatches(HTTP10Bytes) || arrayMatches(HTTPS10Bytes)) {
                            _majorVersion = 1;
                            _minorVersion = 0;
                        }
                        else {
                            String reason =  "Bad HTTP version: " + getString();
                            clearBuffer();
                            shutdownParser();
                            throw new BadResponse(reason);
                        }

                        _lineScheme = getString(bufferPosition() - 4);
                        clearBuffer();

                        // We are through parsing the request line
                        _requestLineState = RequestLineState.SPACE1;

                    case SPACE1:
                        // Eat whitespace
                        for(ch = next(in, false); ch == HttpTokens.SPACE || ch == HttpTokens.TAB; ch = next(in, false));

                        if (ch == 0) return false;

                        if (!HttpTokens.isDigit(ch)) {
                            shutdownParser();
                            throw new BadResponse("Received invalid token when needed code: '" + (char)ch + "'");
                        }
                        _statusCode = 10*_statusCode + (ch - HttpTokens.ZERO);
                        _requestLineState = RequestLineState.STATUS_CODE;

                    case STATUS_CODE:
                        for(ch = next(in, false); HttpTokens.isDigit(ch); ch = next(in, false)) {
                            if (ch == 0) return false;
                            _statusCode = 10*_statusCode + (ch - HttpTokens.ZERO);
                        }

                        if (ch == 0) return false;  // Need more data

                        if (_statusCode < 100 || _statusCode >= 600) {
                            shutdownParser();
                            throw new BadResponse("Invalid status code '" +
                                    _statusCode + "'. Must be between 100 and 599");
                        }

                        if (ch == HttpTokens.LF) {
                        	endResponseLineParsing("");
                            return true;
                        }

                        if (!HttpTokens.isWhiteSpace(ch)) {
                            shutdownParser();
                            throw new BadResponse("Invalid request: Expected SPACE but found '" + (char)ch + "'");
                        }

                        _requestLineState = RequestLineState.SPACE2;

                    case SPACE2:
                        // Eat whitespace
                        for(ch = next(in, false); ch == HttpTokens.SPACE || ch == HttpTokens.TAB; ch = next(in, false));

                        if (ch == 0) return false;

                        if (ch == HttpTokens.LF) {
                        	endResponseLineParsing("");
                            return true;
                        }

                        putChar(ch);
                        _requestLineState = RequestLineState.REASON;

                    case REASON:
                        for(ch = next(in, false); ch != HttpTokens.LF; ch = next(in, false)) {
                            if (ch == 0) return false;
                            putChar(ch);
                        }



                        String reason = getTrimmedString();
                        endResponseLineParsing(reason);
                        return true;

                    default:
                        throw new BaseExceptions.InvalidState("Attempted to parse Response line when already complete." +
                                "RequestLineState: '" + _requestLineState + "'");
                }    // switch
            }        // while loop
        } catch (BaseExceptions.BadRequest ex) {
            shutdownParser();
            throw new BadResponse(ex.getMessage());
        }
    }

    private void endResponseLineParsing(String reason) {
    	clearBuffer();

        // We are through parsing the request line
        _requestLineState = RequestLineState.END;
        submitResponseLine(_statusCode, reason, _lineScheme, _majorVersion, _minorVersion);
    }
}
