package org.javacs.debug.proto;

/**
 * A StepInTarget can be used in the 'stepIn' request and determines into which single target the stepIn request should
 * step.
 */
public class StepInTarget {
    /** Unique identifier for a stepIn target. */
    public int id;
    /** The name of the stepIn target (shown in the UI). */
    public String label;
}
