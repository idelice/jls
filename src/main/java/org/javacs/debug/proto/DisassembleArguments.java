package org.javacs.debug.proto;

/** Arguments for 'disassemble' request. */
public class DisassembleArguments {
    /** Memory reference to the base location containing the instructions to disassemble. */
    public String memoryReference;
    /** Optional offset (in bytes) to be applied to the reference location before disassembling. Can be negative. */
    public Integer offset;
    /**
     * Optional offset (in instructions) to be applied after the byte offset (if any) before disassembling. Can be
     * negative.
     */
    public Integer instructionOffset;
    /**
     * Number of instructions to disassemble starting at the specified location and offset. An adapter must return
     * exactly this number of instructions - any unavailable instructions should be replaced with an
     * implementation-defined 'invalid instruction' value.
     */
    public int instructionCount;
    /** If true, the adapter should attempt to resolve memory addresses and other values to symbolic names. */
    public Boolean resolveSymbols;
}
