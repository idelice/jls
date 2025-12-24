package org.javacs.debug.proto;

public class ContinuedEventBody {
    /** The thread which was continued. */
    public long threadId;
    /** If 'allThreadsContinued' is true, a debug adapter can announce that all threads have continued. */
    public Boolean allThreadsContinued;
}
