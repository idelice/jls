package org.javacs.debug.proto;

/** Detailed information about an exception that has occurred. */
public class ExceptionDetails {
    /** Message contained in the exception. */
    public String message;
    /** Short type name of the exception object. */
    public String typeName;
    /** Fully-qualified type name of the exception object. */
    public String fullTypeName;
    /** Optional expression that can be evaluated in the current scope to obtain the exception object. */
    public String evaluateName;
    /** Stack trace at the time the exception was thrown. */
    public String stackTrace;
    /** Details of the exception contained by this exception, if any. */
    public ExceptionDetails[] innerException;
}
