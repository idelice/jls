package org.javacs.debug.proto;

/** Arguments for 'readMemory' request. */
public class ReadMemoryArguments {
    /** Memory reference to the base location from which data should be read. */
    public String memoryReference;
    /** Optional offset (in bytes) to be applied to the reference location before reading data. Can be negative. */
    public Integer offset;
    /** Number of bytes to read at the specified location and offset. */
    public int count;
}
