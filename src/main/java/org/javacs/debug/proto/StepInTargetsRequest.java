package org.javacs.debug.proto;

/**
 * StepInTargets request; value of command field is 'stepInTargets'. This request retrieves the possible stepIn targets
 * for the specified stack frame. These targets can be used in the 'stepIn' request. The StepInTargets may only be
 * called if the 'supportsStepInTargetsRequest' capability exists and is true.
 */
public class StepInTargetsRequest extends Request {
    public StepInTargetsArguments arguments;
}
