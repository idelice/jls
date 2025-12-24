package org.javacs.debug.proto;

/**
 * Event message for 'stopped' event type. The event indicates that the execution of the debuggee has stopped due to
 * some condition. This can be caused by a break point previously set, a stepping action has completed, by executing a
 * debugger statement etc.
 */
public class StoppedEvent extends Event {
    // event: 'stopped';
    public StoppedEventBody body;
}
