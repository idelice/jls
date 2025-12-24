package org.javacs.debug.proto;

/** Arguments for 'setExceptionBreakpoints' request. */
public class SetExceptionBreakpointsArguments {
    /** IDs of checked exception options. The set of IDs is returned via the 'exceptionBreakpointFilters' capability. */
    public String[] filters;
    /** Configuration options for selected exceptions. */
    public ExceptionOptions[] exceptionOptions;
}
