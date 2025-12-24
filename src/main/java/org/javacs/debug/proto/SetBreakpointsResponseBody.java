package org.javacs.debug.proto;

public class SetBreakpointsResponseBody {
    /**
     * Information about the breakpoints. The array elements are in the same order as the elements of the 'breakpoints'
     * (or the deprecated 'lines') array in the arguments.
     */
    public Breakpoint[] breakpoints;
}
