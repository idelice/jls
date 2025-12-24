package org.javacs.debug.proto;

/** Event message for 'output' event type. The event indicates that the target has produced some output. */
public class OutputEvent extends Event {
    // event: 'output';
    public OutputEventBody body;
}
