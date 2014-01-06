package blaze.http_parser;


/**
 * @author Bryce Anderson
 *         Created on 1/2/14
 */
public class BaseExceptions {

    static abstract class ParserException extends Exception {

        public ParserException(String msg) {
            super(msg);
        }

        public final String msg() {
            return this.getMessage();
        }

        // We will be using this for
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    public static final class BadRequest extends ParserException {
        private final int code;

        public BadRequest(int code, String msg) {
            super(msg);
            this.code = code;
        }

        public int getCode() { return code; }
    }

    public static final class ParsingError extends ParserException {
        public ParsingError(String error) {
            super(error);
        }
    }

    public static final class ExternalExeption extends ParserException {

        private final Exception cause;

        public ExternalExeption(Exception cause, String stage) {
            super("Caught external exception in stage " + stage + ": " + cause);
            this.cause = cause;
        }

        @Override
        public synchronized Throwable getCause() {
            return cause;    //To change body of overridden methods use File | Settings | File Templates.
        }
    }

    public static final class InvalidState extends ParserException {
        public InvalidState(String msg) {
            super(msg);
        }
    }
}
