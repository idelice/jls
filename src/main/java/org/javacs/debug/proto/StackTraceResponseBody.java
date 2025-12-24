package org.javacs.debug.proto;

public class StackTraceResponseBody {
    /**
     * The frames of the stackframe. If the array has length zero, there are no stackframes available. This means that
     * there is no location information available.
     */
    public StackFrame[] stackFrames;
    /** The total number of frames available. */
    public Integer totalFrames;
}
