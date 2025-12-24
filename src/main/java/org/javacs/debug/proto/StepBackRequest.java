package org.javacs.debug.proto;

/**
 * StepBack request; value of command field is 'stepBack'. The request starts the debuggee to run one step backwards.
 * The debug adapter first sends the response and then a 'stopped' event (with reason 'step') after the step has
 * completed. Clients should only call this request if the capability 'supportsStepBack' is true.
 */
public class StepBackRequest extends Request {
    public StepBackArguments arguments;
}
