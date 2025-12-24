package org.javacs.debug.proto;

/**
 * ReverseContinue request; value of command field is 'reverseContinue'. The request starts the debuggee to run
 * backward. Clients should only call this request if the capability 'supportsStepBack' is true.
 */
public class ReverseContinueRequest extends Request {
    public ReverseContinueArguments arguments;
}
