package org.javacs.debug.proto;

/**
 * Event message for 'terminated' event type. The event indicates that debugging of the debuggee has terminated. This
 * does **not** mean that the debuggee itself has exited.
 */
public class TerminatedEvent extends Event {
    // event: 'terminated';
    public TerminatedEventBody body;
}
