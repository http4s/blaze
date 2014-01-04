package http_parser;

/**
 * @author Bryce Anderson
 *         Created on 1/2/14
 */
public class BaseExceptions {

    public final static NeedsInput needsInput = new NeedsInput();

    static abstract class BaseParseExcept extends Exception {

        public BaseParseExcept(String msg) {
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

    public static final class Complete extends BaseParseExcept {
        public Complete() {
            super("Parsing Complete");
        }
    }

    public static final class BadRequest extends BaseParseExcept {
        private final int code;

        public BadRequest(int code, String msg) {
            super(msg);
            this.code = code;
        }

        public int getCode() { return code; }
    }

    public static final class ParsingError extends BaseParseExcept {
        public ParsingError(String error) {
            super(error);
        }
    }

    public static final class ExternalExeption extends BaseParseExcept {

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

    public static final class NeedsInput extends BaseParseExcept {
        public NeedsInput() {
            super("Input needed");
        }
    }
}
