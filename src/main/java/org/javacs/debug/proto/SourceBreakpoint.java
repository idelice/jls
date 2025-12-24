package org.javacs.debug.proto;

/** Properties of a breakpoint or logpoint passed to the setBreakpoints request. */
public class SourceBreakpoint {
    /** The source line of the breakpoint or logpoint. */
    public int line;
    /** An optional source column of the breakpoint. */
    public Integer column;
    /** An optional expression for conditional breakpoints. */
    public String condition;
    /**
     * An optional expression that controls how many hits of the breakpoint are ignored. The backend is expected to
     * interpret the expression as needed.
     */
    public String hitCondition;
    /**
     * If this attribute exists and is non-empty, the backend must not 'break' (stop) but log the message instead.
     * Expressions within {} are interpolated.
     */
    public String logMessage;
}
