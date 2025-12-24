package org.javacs.debug.proto;

/** Information about a Breakpoint created in setBreakpoints or setFunctionBreakpoints. */
public class Breakpoint {
    /**
     * An optional identifier for the breakpoint. It is needed if breakpoint events are used to update or remove
     * breakpoints.
     */
    public Integer id;
    /** If true breakpoint could be set (but not necessarily at the desired location). */
    public boolean verified;
    /**
     * An optional message about the state of the breakpoint. This is shown to the user and can be used to explain why a
     * breakpoint could not be verified.
     */
    public String message;
    /** The source where the breakpoint is located. */
    public Source source;
    /** The start line of the actual range covered by the breakpoint. */
    public Integer line;
    /** An optional start column of the actual range covered by the breakpoint. */
    public Integer column;
    /** An optional end line of the actual range covered by the breakpoint. */
    public Integer endLine;
    /**
     * An optional end column of the actual range covered by the breakpoint. If no end line is given, then the end
     * column is assumed to be in the start line.
     */
    public Integer endColumn;
}
