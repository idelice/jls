package org.javacs.debug.proto;

/** Arguments for 'continue' request. */
public class ContinueArguments {
    /**
     * Continue execution for the specified thread (if possible). If the backend cannot continue on a single thread but
     * will continue on all threads, it should set the 'allThreadsContinued' attribute in the response to true.
     */
    public Long threadId;
}
