package org.javacs.debug.proto;

/**
 * SetDataBreakpoints request; value of command field is 'setDataBreakpoints'. Replaces all existing data breakpoints
 * with new data breakpoints. To clear all data breakpoints, specify an empty array. When a data breakpoint is hit, a
 * 'stopped' event (with reason 'data breakpoint') is generated.
 */
public class SetDataBreakpointsRequest extends Request {
    public SetDataBreakpointsArguments arguments;
}
