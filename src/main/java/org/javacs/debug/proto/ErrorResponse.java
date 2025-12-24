package org.javacs.debug.proto;

/** On error (whenever 'success' is false), the body can provide more details. */
public class ErrorResponse extends Response {
    public ErrorResponseBody Body;
}
