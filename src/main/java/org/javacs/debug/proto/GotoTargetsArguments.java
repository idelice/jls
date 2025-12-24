package org.javacs.debug.proto;

/** Arguments for 'gotoTargets' request. */
public class GotoTargetsArguments {
    /** The source location for which the goto targets are determined. */
    public Source source;
    /** The line location for which the goto targets are determined. */
    public int line;
    /** An optional column location for which the goto targets are determined. */
    public Integer column;
}
