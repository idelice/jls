package org.javacs.debug.proto;

/** Response to 'setDataBreakpoints' request. Returned is information about each breakpoint created by this request. */
public class SetDataBreakpointsResponse extends Response {
    public SetDataBreakpointsResponseBody body;
}
