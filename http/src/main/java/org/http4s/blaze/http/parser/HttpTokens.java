package org.http4s.blaze.http.parser;

import org.http4s.blaze.http.parser.BaseExceptions.BadCharacter;

public final class HttpTokens {
  // Needs more input
  static final char EMPTY_BUFF = 0xFFFF;

  // replacement for invalid octets
  static final char REPLACEMENT = 0xFFFD;

  // Terminal symbols.
  static final char COLON = ':';
  static final char TAB = '\t';
  static final char LF = '\n';
  static final char CR = '\r';
  static final char SPACE = ' ';
  static final char[] CRLF = {CR, LF};
  static final char SEMI_COLON = ';';

  static final byte ZERO = '0';
  static final byte NINE = '9';
  static final byte A = 'A';
  static final byte F = 'F';
  static final byte Z = 'Z';
  static final byte a = 'a';
  static final byte f = 'f';
  static final byte z = 'z';

  public static int hexCharToInt(final char ch) throws BadCharacter {
    if (ZERO <= ch && ch <= NINE) {
      return ch - ZERO;
    } else if (a <= ch && ch <= f) {
      return ch - a + 10;
    } else if (A <= ch && ch <= F) {
      return ch - A + 10;
    } else {
      throw new BadCharacter("Bad hex char: " + ch);
    }
  }

  public static boolean isDigit(final char ch) {
    return HttpTokens.NINE >= ch && ch >= HttpTokens.ZERO;
  }

  public static boolean isHexChar(byte ch) {
    return ZERO <= ch && ch <= NINE || a <= ch && ch <= f || A <= ch && ch <= F;
  }

  public static boolean isWhiteSpace(char ch) {
    return ch == HttpTokens.SPACE || ch == HttpTokens.TAB;
  }
}
