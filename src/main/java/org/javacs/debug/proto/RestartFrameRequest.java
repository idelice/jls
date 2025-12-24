package org.javacs.debug.proto;

/**
 * RestartFrame request; value of command field is 'restartFrame'. The request restarts execution of the specified
 * stackframe. The debug adapter first sends the response and then a 'stopped' event (with reason 'restart') after the
 * restart has completed.
 */
public class RestartFrameRequest extends Request {
    public RestartFrameArguments arguments;
}
