package org.javacs.debug.proto;

/**
 * Event message for 'process' event type. The event indicates that the debugger has begun debugging a new process.
 * Either one that it has launched, or one that it has attached to.
 */
public class ProcessEvent extends Event {
    // event: 'process';
    public ProcessEventBody body;
}
