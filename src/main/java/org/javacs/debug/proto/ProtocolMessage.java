package org.javacs.debug.proto;

/** Base class of requests, responses, and events. */
public class ProtocolMessage {
    /** Sequence number. */
    public int seq;
    /** Message type. Values: 'request', 'response', 'event', etc. */
    public String type;
}
