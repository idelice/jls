package org.javacs.debug.proto;

/** Properties of a data breakpoint passed to the setDataBreakpoints request. */
public class DataBreakpoint {
    /** An id representing the data. This id is returned from the dataBreakpointInfo request. */
    public String dataId;
    /** The access type of the data. 'read' | 'write' | 'readWrite'. */
    public String accessType;
    /** An optional expression for conditional breakpoints. */
    public String condition;
    /**
     * An optional expression that controls how many hits of the breakpoint are ignored. The backend is expected to
     * interpret the expression as needed.
     */
    public String hitCondition;
}
