package org.javacs.debug.proto;

/**
 * StepOut request; value of command field is 'stepOut'. The request starts the debuggee to run again for one step. The
 * debug adapter first sends the response and then a 'stopped' event (with reason 'step') after the step has completed.
 */
public class StepOutRequest extends Request {
    public StepOutArguments arguments;
}
