package org.javacs.debug.proto;

/**
 * Attach request; value of command field is 'attach'. The attach request is sent from the client to the debug adapter
 * to attach to a debuggee that is already running. Since attaching is debugger/runtime specific, the arguments for this
 * request are not part of this specification.
 */
public class AttachRequest extends Request {
    public AttachRequestArguments arguments;
}
