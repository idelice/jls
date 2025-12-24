package org.javacs.debug.proto;

/** Arguments for 'stepIn' request. */
public class StepInArguments {
    /** Execute 'stepIn' for this thread. */
    public long threadId;
    /** Optional id of the target to step into. */
    public Integer targetId;
}
