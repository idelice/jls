package org.javacs.debug.proto;

/**
 * RunInTerminal request; value of command field is 'runInTerminal'. This request is sent from the debug adapter to the
 * client to run a command in a terminal. This is typically used to launch the debuggee in a terminal provided by the
 * client.
 */
public class RunInTerminalRequest extends Request {
    public RunInTerminalRequestArguments arguments;
}
