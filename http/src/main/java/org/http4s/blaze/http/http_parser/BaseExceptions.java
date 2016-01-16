package org.http4s.blaze.http.http_parser;

public class BaseExceptions {

    public static abstract class ParserException extends Exception {

        public ParserException(String msg) {
            super(msg);
        }

        // We will be using this for
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    // TODO: make exceptions that can be represented as a response
    public static class BadRequest extends ParserException {
        public BadRequest(String msg) {
            super(msg);
        }
    }

    public static class BadCharacter extends BadRequest {
        public BadCharacter(String msg) {
            super(msg);
        }
    }

    public static class BadResponse extends ParserException {
        public BadResponse(String msg) {
            super(msg);
        }
    }

    public static class InvalidState extends ParserException {
        public InvalidState(String msg) {
            super(msg);
        }
    }
}
