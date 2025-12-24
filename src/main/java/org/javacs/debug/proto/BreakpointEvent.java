package org.javacs.debug.proto;

/**
 * Event message for 'breakpoint' event type. The event indicates that some information about a breakpoint has changed.
 */
public class BreakpointEvent extends Event {
    // event: 'breakpoint';
    public BreakpointEventBody body;
}
