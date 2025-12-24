package org.javacs.debug.proto;

/**
 * Launch request; value of command field is 'launch'. The launch request is sent from the client to the debug adapter
 * to start the debuggee with or without debugging (if 'noDebug' is true). Since launching is debugger/runtime specific,
 * the arguments for this request are not part of this specification.
 */
public class LaunchRequest extends Request {
    public LaunchRequestArguments arguments;
}
