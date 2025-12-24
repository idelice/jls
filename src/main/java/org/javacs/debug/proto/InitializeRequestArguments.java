package org.javacs.debug.proto;

/** Arguments for 'initialize' request. */
public class InitializeRequestArguments {
    /** The ID of the (frontend) client using this adapter. */
    public String clientID;
    /** The human readable name of the (frontend) client using this adapter. */
    public String clientName;
    /** The ID of the debug adapter. */
    public String adapterID;
    /** The ISO-639 locale of the (frontend) client using this adapter, e.g. en-US or de-CH. */
    public String locale;
    /** If true all line numbers are 1-based (default). */
    public Boolean linesStartAt1;
    /** If true all column numbers are 1-based (default). */
    public Boolean columnsStartAt1;
    /**
     * Determines in what format paths are specified. The default is 'path', which is the native format. Values: 'path',
     * 'uri', etc.
     */
    public String pathFormat;
    /** Client supports the optional type attribute for variables. */
    public Boolean supportsVariableType;
    /** Client supports the paging of variables. */
    public Boolean supportsVariablePaging;
    /** Client supports the runInTerminal request. */
    public Boolean supportsRunInTerminalRequest;
    /** Client supports memory references. */
    public Boolean supportsMemoryReferences;
}
