package org.javacs.debug.proto;

public class ExceptionInfoResponseBody {
    /** ID of the exception that was thrown. */
    public String exceptionId;
    /** Descriptive text for the exception provided by the debug adapter. */
    public String description;
    /**
     * Mode that caused the exception notification to be raised. never: never breaks, always: always breaks, unhandled:
     * breaks when excpetion unhandled, userUnhandled: breaks if the exception is not handled by user code.
     */
    public String breakMode;
    /** Detailed information about the exception. */
    public ExceptionDetails details;
}
