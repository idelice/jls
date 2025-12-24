package org.javacs.debug.proto;

/** Response for a request. */
public class Response extends ProtocolMessage {
    // type: 'response';
    /** Sequence number of the corresponding request. */
    public int request_seq;
    /** Outcome of the request. */
    public boolean success;
    /** The command requested. */
    public String command;
    /** Contains error message if success == false. */
    public String message;
}
