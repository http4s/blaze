package http_parser;

/**
 * @author Bryce Anderson
 *         Created on 1/2/14
 */

// Taken directly from Jetty

public interface HttpTokens
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

    public enum EndOfContent { UNKNOWN_CONTENT,NO_CONTENT,EOF_CONTENT,CONTENT_LENGTH,CHUNKED_CONTENT,SELF_DEFINING_CONTENT }

}