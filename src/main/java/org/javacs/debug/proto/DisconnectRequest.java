package org.javacs.debug.proto;

/**
 * Disconnect request; value of command field is 'disconnect'. The 'disconnect' request is sent from the client to the
 * debug adapter in order to stop debugging. It asks the debug adapter to disconnect from the debuggee and to terminate
 * the debug adapter. If the debuggee has been started with the 'launch' request, the 'disconnect' request terminates
 * the debuggee. If the 'attach' request was used to connect to the debuggee, 'disconnect' does not terminate the
 * debuggee. This behavior can be controlled with the 'terminateDebuggee' argument (if supported by the debug adapter).
 */
public class DisconnectRequest extends Request {
    public DisconnectArguments arguments;
}
