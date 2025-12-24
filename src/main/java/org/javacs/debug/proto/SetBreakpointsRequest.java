package org.javacs.debug.proto;

/**
 * SetBreakpoints request; value of command field is 'setBreakpoints'. Sets multiple breakpoints for a single source and
 * clears all previous breakpoints in that source. To clear all breakpoint for a source, specify an empty array. When a
 * breakpoint is hit, a 'stopped' event (with reason 'breakpoint') is generated.
 */
public class SetBreakpointsRequest extends Request {
    public SetBreakpointsArguments arguments;
}
