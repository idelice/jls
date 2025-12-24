package org.javacs.debug.proto;

/**
 * Goto request; value of command field is 'goto'. The request sets the location where the debuggee will continue to
 * run. This makes it possible to skip the execution of code or to executed code again. The code between the current
 * location and the goto target is not executed but skipped. The debug adapter first sends the response and then a
 * 'stopped' event with reason 'goto'.
 */
public class GotoRequest extends Request {
    public GotoArguments arguments;
}
