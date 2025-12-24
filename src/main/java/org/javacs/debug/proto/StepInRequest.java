package org.javacs.debug.proto;

/**
 * StepIn request; value of command field is 'stepIn'. The request starts the debuggee to step into a function/method if
 * possible. If it cannot step into a target, 'stepIn' behaves like 'next'. The debug adapter first sends the response
 * and then a 'stopped' event (with reason 'step') after the step has completed. If there are multiple function/method
 * calls (or other targets) on the source line, the optional argument 'targetId' can be used to control into which
 * target the 'stepIn' should occur. The list of possible targets for a given source line can be retrieved via the
 * 'stepInTargets' request.
 */
public class StepInRequest extends Request {
    public StepInArguments arguments;
}
