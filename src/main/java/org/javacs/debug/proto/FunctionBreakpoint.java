package org.javacs.debug.proto;

/** Properties of a breakpoint passed to the setFunctionBreakpoints request. */
public class FunctionBreakpoint {
    /** The name of the function. */
    public String name;
    /** An optional expression for conditional breakpoints. */
    public String condition;
    /**
     * An optional expression that controls how many hits of the breakpoint are ignored. The backend is expected to
     * interpret the expression as needed.
     */
    public String hitCondition;
}
