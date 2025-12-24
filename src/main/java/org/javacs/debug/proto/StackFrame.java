package org.javacs.debug.proto;

/** A Stackframe contains the source location. */
public class StackFrame {
    /**
     * An identifier for the stack frame. It must be unique across all threads. This id can be used to retrieve the
     * scopes of the frame with the 'scopesRequest' or to restart the execution of a stackframe.
     */
    public long id;
    /** The name of the stack frame, typically a method name. */
    public String name;
    /** The optional source of the frame. */
    public Source source;
    /** The line within the file of the frame. If source is null or doesn't exist, line is 0 and must be ignored. */
    public int line;
    /** The column within the line. If source is null or doesn't exist, column is 0 and must be ignored. */
    public int column;
    /** An optional end line of the range covered by the stack frame. */
    public Integer endLine;
    /** An optional end column of the range covered by the stack frame. */
    public Integer endColumn;
    /** Optional memory reference for the current instruction pointer in this frame. */
    public String instructionPointerReference;
    /** The module associated with this frame, if any. */
    public String moduleId;
    /**
     * An optional hint for how to present this frame in the UI. A value of 'label' can be used to indicate that the
     * frame is an artificial frame that is used as a visual label or separator. A value of 'subtle' can be used to
     * change the appearance of a frame in a 'subtle' way. 'normal' | 'label' | 'subtle'.
     */
    public String presentationHint;
}
