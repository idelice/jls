package org.javacs.debug.proto;

/**
 * TerminateThreads request; value of command field is 'terminateThreads'. The request terminates the threads with the
 * given ids.
 */
public class TerminateThreadsRequest extends Request {
    public TerminateThreadsArguments arguments;
}
