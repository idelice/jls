package org.javacs.debug.proto;

/** Arguments for 'source' request. */
public class SourceArguments {
    /** Specifies the source content to load. Either source.path or source.sourceReference must be specified. */
    public Source source;
    /**
     * The reference to the source. This is the same as source.sourceReference. This is provided for backward
     * compatibility since old backends do not understand the 'source' attribute.
     */
    public int sourceReference;
}
