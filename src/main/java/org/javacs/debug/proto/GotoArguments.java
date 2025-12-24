package org.javacs.debug.proto;

/** Arguments for 'goto' request. */
public class GotoArguments {
    /** Set the goto target for this thread. */
    public long threadId;
    /** The location where the debuggee will continue to run. */
    public int targetId;
}
