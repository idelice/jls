package org.javacs.debug.proto;

/**
 * Event message for 'exited' event type. The event indicates that the debuggee has exited and returns its exit code.
 */
public class ExitedEvent extends Event {
    // event: 'exited';
    public ExitedEventBody body;
}
