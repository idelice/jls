package org.javacs.debug.proto;

/** Arguments for 'disconnect' request. */
public class DisconnectArguments {
    /** A value of true indicates that this 'disconnect' request is part of a restart sequence. */
    public Boolean restart;
    /**
     * Indicates whether the debuggee should be terminated when the debugger is disconnected. If unspecified, the debug
     * adapter is free to do whatever it thinks is best. A client can only rely on this attribute being properly honored
     * if a debug adapter returns true for the 'supportTerminateDebuggee' capability.
     */
    public Boolean terminateDebuggee;
}
