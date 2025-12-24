package org.javacs.debug.proto;

/** Arguments for 'stepInTargets' request. */
public class StepInTargetsArguments {
    /** The stack frame for which to retrieve the possible stepIn targets. */
    public long frameId;
}
