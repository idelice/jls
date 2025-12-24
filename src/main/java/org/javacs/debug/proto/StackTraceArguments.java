package org.javacs.debug.proto;

/** Arguments for 'stackTrace' request. */
public class StackTraceArguments {
    /** Retrieve the stacktrace for this thread. */
    public long threadId;
    /** The index of the first frame to return; if omitted frames start at 0. */
    public int startFrame;
    /** The maximum number of frames to return. If levels is not specified or 0, all frames are returned. */
    public Integer levels;
    /** Specifies details on how to format the stack frames. */
    public StackFrameFormat format;
}
