package org.javacs.debug.proto;

public class ProcessEventBody {
    /**
     * The logical name of the process. This is usually the full path to process's executable file. Example:
     * /home/example/myproj/program.js.
     */
    public String name;
    /** The system process id of the debugged process. This property will be missing for non-system processes. */
    public Integer systemProcessId;
    /** If true, the process is running on the same computer as the debug adapter. */
    public Boolean isLocalProcess;
    /**
     * Describes how the debug engine started debugging this process. 'launch': Process was launched under the debugger.
     * 'attach': Debugger attached to an existing process. 'attachForSuspendedLaunch': A project launcher component has
     * launched a new process in a suspended state and then asked the debugger to attach.
     */
    public String startMethod;
    /**
     * The size of a pointer or address for this process, in bits. This value may be used by clients when formatting
     * addresses for display.
     */
    public Integer pointerSize;
}
