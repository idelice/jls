package org.javacs.debug.proto;

/** The checksum of an item calculated by the specified algorithm. */
public class Checksum {
    /** The algorithm used to calculate this checksum. 'MD5' | 'SHA1' | 'SHA256' | 'timestamp'. */
    public String algorithm;
    /** Value of the checksum. */
    public String checksum;
}
