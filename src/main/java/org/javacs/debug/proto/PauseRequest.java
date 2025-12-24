package org.javacs.debug.proto;

/**
 * Pause request; value of command field is 'pause'. The request suspenses the debuggee. The debug adapter first sends
 * the response and then a 'stopped' event (with reason 'pause') after the thread has been paused successfully.
 */
public class PauseRequest extends Request {
    public PauseArguments arguments;
}
