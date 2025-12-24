package org.javacs.debug.proto;

/** Information about the capabilities of a debug adapter. */
public class Capabilities {
    /** The debug adapter supports the 'configurationDone' request. */
    public Boolean supportsConfigurationDoneRequest;
    /** The debug adapter supports function breakpoints. */
    public Boolean supportsFunctionBreakpoints;
    /** The debug adapter supports conditional breakpoints. */
    public Boolean supportsConditionalBreakpoints;
    /** The debug adapter supports breakpoints that break execution after a specified number of hits. */
    public Boolean supportsHitConditionalBreakpoints;
    /** The debug adapter supports a (side effect free) evaluate request for data hovers. */
    public Boolean supportsEvaluateForHovers;
    /** Available filters or options for the setExceptionBreakpoints request. */
    public ExceptionBreakpointsFilter[] exceptionBreakpointFilters;
    /** The debug adapter supports stepping back via the 'stepBack' and 'reverseContinue' requests. */
    public Boolean supportsStepBack;
    /** The debug adapter supports setting a variable to a value. */
    public Boolean supportsSetVariable;
    /** The debug adapter supports restarting a frame. */
    public Boolean supportsRestartFrame;
    /** The debug adapter supports the 'gotoTargets' request. */
    public Boolean supportsGotoTargetsRequest;
    /** The debug adapter supports the 'stepInTargets' request. */
    public Boolean supportsStepInTargetsRequest;
    /** The debug adapter supports the 'completions' request. */
    public Boolean supportsCompletionsRequest;
    /** The debug adapter supports the 'modules' request. */
    public Boolean supportsModulesRequest;
    /** The set of additional module information exposed by the debug adapter. */
    public ColumnDescriptor[] additionalModuleColumns;
    /** Checksum algorithms supported by the debug adapter. 'MD5' | 'SHA1' | 'SHA256' | 'timestamp'. */
    public String[] supportedChecksumAlgorithms;
    /**
     * The debug adapter supports the 'restart' request. In this case a client should not implement 'restart' by
     * terminating and relaunching the adapter but by calling the RestartRequest.
     */
    public Boolean supportsRestartRequest;
    /** The debug adapter supports 'exceptionOptions' on the setExceptionBreakpoints request. */
    public Boolean supportsExceptionOptions;
    /**
     * The debug adapter supports a 'format' attribute on the stackTraceRequest, variablesRequest, and evaluateRequest.
     */
    public Boolean supportsValueFormattingOptions;
    /** The debug adapter supports the 'exceptionInfo' request. */
    public Boolean supportsExceptionInfoRequest;
    /** The debug adapter supports the 'terminateDebuggee' attribute on the 'disconnect' request. */
    public Boolean supportTerminateDebuggee;
    /**
     * The debug adapter supports the delayed loading of parts of the stack, which requires that both the 'startFrame'
     * and 'levels' arguments and the 'totalFrames' result of the 'StackTrace' request are supported.
     */
    public Boolean supportsDelayedStackTraceLoading;
    /** The debug adapter supports the 'loadedSources' request. */
    public Boolean supportsLoadedSourcesRequest;
    /** The debug adapter supports logpoints by interpreting the 'logMessage' attribute of the SourceBreakpoint. */
    public Boolean supportsLogPoints;
    /** The debug adapter supports the 'terminateThreads' request. */
    public Boolean supportsTerminateThreadsRequest;
    /** The debug adapter supports the 'setExpression' request. */
    public Boolean supportsSetExpression;
    /** The debug adapter supports the 'terminate' request. */
    public Boolean supportsTerminateRequest;
    /** The debug adapter supports data breakpoints. */
    public Boolean supportsDataBreakpoints;
    /** The debug adapter supports the 'readMemory' request. */
    public Boolean supportsReadMemoryRequest;
    /** The debug adapter supports the 'disassemble' request. */
    public Boolean supportsDisassembleRequest;
}
