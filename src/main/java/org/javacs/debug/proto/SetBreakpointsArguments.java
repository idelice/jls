package org.javacs.debug.proto;

/** Arguments for 'setBreakpoints' request. */
public class SetBreakpointsArguments {
    /** The source location of the breakpoints; either 'source.path' or 'source.reference' must be specified. */
    public Source source = new Source();
    /** The code locations of the breakpoints. */
    public SourceBreakpoint[] breakpoints;
    /**
     * A value of true indicates that the underlying source has been modified which results in new breakpoint locations.
     */
    public Boolean sourceModified;
}
