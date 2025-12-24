package org.javacs.debug.proto;

public class ReadMemoryResponseBody {
    /**
     * The address of the first byte of data returned. Treated as a hex value if prefixed with '0x', or as a decimal
     * value otherwise.
     */
    public String address;
    /**
     * The number of unreadable bytes encountered after the last successfully read byte. This can be used to determine
     * the number of bytes that must be skipped before a subsequent 'readMemory' request will succeed.
     */
    public Integer unreadableBytes;
    /** The bytes read from memory, encoded using base64. */
    public String data;
}
