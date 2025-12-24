package org.javacs.debug.proto;

public class LoadedSourceEventBody {
    /** The reason for the event. 'new' | 'changed' | 'removed'. */
    public String reason;
    /** The new, changed, or removed source. */
    public Source source;
}
