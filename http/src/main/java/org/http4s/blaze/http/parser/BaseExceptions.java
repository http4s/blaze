package org.http4s.blaze.http.parser;

public class BaseExceptions {

    public static abstract class ParserException extends Exception {
        public ParserException(String msg) {
            super(msg);
        }
    }

    public static class BadMessage extends ParserException {
        public BadMessage(String msg) {
            super(msg);
        }
    }

    public static class BadCharacter extends BadMessage {
        public BadCharacter(String msg) {
            super(msg);
        }
    }

    public static class InvalidState extends ParserException {
        public InvalidState(String msg) {
            super(msg);
        }
    }
}
