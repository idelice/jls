package org.javacs.debug.proto;

/**
 * Next request; value of command field is 'next'. The request starts the debuggee to run again for one step. The debug
 * adapter first sends the response and then a 'stopped' event (with reason 'step') after the step has completed.
 */
public class NextRequest extends Request {
    public NextArguments arguments;
}
