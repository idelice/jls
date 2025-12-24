package org.javacs.debug.proto;

/** Provides formatting information for a stack frame. */
public class StackFrameFormat extends ValueFormat {
    /** Displays parameters for the stack frame. */
    public Boolean parameters;
    /** Displays the types of parameters for the stack frame. */
    public Boolean parameterTypes;
    /** Displays the names of parameters for the stack frame. */
    public Boolean parameterNames;
    /** Displays the values of parameters for the stack frame. */
    public Boolean parameterValues;
    /** Displays the line number of the stack frame. */
    public Boolean line;
    /** Displays the module of the stack frame. */
    public Boolean module;
    /** Includes all stack frames, including those the debug adapter might otherwise hide. */
    public Boolean includeAll;
}
