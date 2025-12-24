package org.javacs.debug.proto;

/**
 * Terminate request; value of command field is 'terminate'. The 'terminate' request is sent from the client to the
 * debug adapter in order to give the debuggee a chance for terminating itself.
 */
public class TerminateRequest extends Request {
    public TerminateArguments arguments;
}
