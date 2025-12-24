package org.javacs.debug.proto;

/** Arguments for 'setFunctionBreakpoints' request. */
public class SetFunctionBreakpointsArguments {
    /** The function names of the breakpoints. */
    public FunctionBreakpoint[] breakpoints;
}
