package org.javacs.debug.proto;

public class ContinueResponseBody {
    /**
     * If true, the 'continue' request has ignored the specified thread and continued all threads instead. If this
     * attribute is missing a value of 'true' is assumed for backward compatibility.
     */
    public Boolean allThreadsContinued;
}
