package org.javacs.debug.proto;

/**
 * ExceptionInfo request; value of command field is 'exceptionInfo'. Retrieves the details of the exception that caused
 * this event to be raised.
 */
public class ExceptionInfoRequest extends Request {
    public ExceptionInfoArguments arguments;
}
