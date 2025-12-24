package org.javacs.debug.proto;

/**
 * Response to 'setBreakpoints' request. Returned is information about each breakpoint created by this request. This
 * includes the actual code location and whether the breakpoint could be verified. The breakpoints returned are in the
 * same order as the elements of the 'breakpoints' (or the deprecated 'lines') array in the arguments.
 */
public class SetBreakpointsResponse extends Response {
    public SetBreakpointsResponseBody body;
}
