package org.javacs.debug.proto;

/**
 * Initialize request; value of command field is 'initialize'. The 'initialize' request is sent as the first request
 * from the client to the debug adapter in order to configure it with client capabilities and to retrieve capabilities
 * from the debug adapter. Until the debug adapter has responded to with an 'initialize' response, the client must not
 * send any additional requests or events to the debug adapter. In addition the debug adapter is not allowed to send any
 * requests or events to the client until it has responded with an 'initialize' response. The 'initialize' request may
 * only be sent once.
 */
public class InitializeRequest extends Request {
    public InitializeRequestArguments arguments;
}
