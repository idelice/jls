package org.javacs.debug.proto;

/** Event message for 'thread' event type. The event indicates that a thread has started or exited. */
public class ThreadEvent extends Event {
    // event: 'thread';
    public ThreadEventBody body;
}
