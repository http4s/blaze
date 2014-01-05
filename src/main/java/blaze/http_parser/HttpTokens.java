package blaze.http_parser;

import blaze.http_parser.BaseExceptions.ParsingError;

/**
 * @author Bryce Anderson
 *         Created on 1/2/14
 */

// Taken directly from Jetty

public final class HttpTokens
{
    // Terminal symbols.
    static final byte COLON= (byte)':';
    static final byte TAB= 0x09;
    static final byte LF = 0x0A;
    static final byte CR = 0x0D;
    static final byte SPACE= 0x20;
    static final byte[] CRLF = {CR, LF};
    static final byte SEMI_COLON= (byte)';';

    final static byte ZERO = (byte)'0';
    final static byte NINE = (byte)'9';
    final static byte A = (byte)'A';
    final static byte F = (byte)'F';
    final static byte Z = (byte)'Z';
    final static byte a = (byte)'a';
    final static byte f = (byte)'f';
    final static byte z = (byte)'z';

    public static int hexCharToInt(final byte ch) throws ParsingError {
        if (ZERO <= ch && ch <= NINE) {
            return ch - ZERO;
        }
        else if (a <= ch && ch <= f) {
            return ch - a + 10;
        }
        else if (A <= ch && ch <= F) {
            return ch - A + 10;
        }
        else {
            throw new ParsingError("Bad hex char: " + (char)ch);
        }
    }

    public static boolean isDigit(byte ch) {
        return HttpTokens.NINE >= ch && ch >= HttpTokens.ZERO;
    }

    public static boolean isHexChar(byte ch) {
        return ZERO <= ch && ch <= NINE ||
                  a <= ch && ch <= f    ||
                  A <= ch && ch <= F;
    }

    public static boolean isWhiteSpace(byte ch) {
        return ch == HttpTokens.SPACE || ch == HttpTokens.TAB;
    }

    public enum EndOfContent { UNKNOWN_CONTENT,NO_CONTENT,EOF_CONTENT,CONTENT_LENGTH,CHUNKED_CONTENT,SELF_DEFINING_CONTENT }

}