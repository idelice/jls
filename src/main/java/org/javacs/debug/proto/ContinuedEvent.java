package org.javacs.debug.proto;

/**
 * Event message for 'continued' event type. The event indicates that the execution of the debuggee has continued.
 * Please note: a debug adapter is not expected to send this event in response to a request that implies that execution
 * continues, e.g. 'launch' or 'continue'. It is only necessary to send a 'continued' event if there was no previous
 * request that implied this.
 */
public class ContinuedEvent extends Event {
    // event: 'continued';
    public ContinuedEventBody body;
}
