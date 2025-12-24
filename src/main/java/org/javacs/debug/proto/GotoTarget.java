package org.javacs.debug.proto;

/**
 * A GotoTarget describes a code location that can be used as a target in the 'goto' request. The possible goto targets
 * can be determined via the 'gotoTargets' request.
 */
public class GotoTarget {
    /** Unique identifier for a goto target. This is used in the goto request. */
    public int id;
    /** The name of the goto target (shown in the UI). */
    public String label;
    /** The line of the goto target. */
    public int line;
    /** An optional column of the goto target. */
    public Integer column;
    /** An optional end line of the range covered by the goto target. */
    public Integer endLine;
    /** An optional end column of the range covered by the goto target. */
    public Integer endColumn;
    /** Optional memory reference for the instruction pointer value represented by this target. */
    public String instructionPointerReference;
}
