package org.javacs.debug.proto;

/** ReadMemory request; value of command field is 'readMemory'. Reads bytes from memory at the provided location. */
public class ReadMemoryRequest extends Request {
    public ReadMemoryArguments arguments;
}
