package org.javacs.debug.proto;

public class BreakpointEventBody {
    /** The reason for the event. Values: 'changed', 'new', 'removed', etc. */
    public String reason;
    /** The 'id' attribute is used to find the target breakpoint and the other attributes are used as the new values. */
    public Breakpoint breakpoint;
}
