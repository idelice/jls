package org.javacs.debug.proto;

/** A Scope is a named container for variables. Optionally a scope can map to a source or a range within a source. */
public class Scope {
    /**
     * Name of the scope such as 'Arguments', 'Locals', or 'Registers'. This string is shown in the UI as is and can be
     * translated.
     */
    public String name;
    /**
     * An optional hint for how to present this scope in the UI. If this attribute is missing, the scope is shown with a
     * generic UI. Values: 'arguments': Scope contains method arguments. 'locals': Scope contains local variables.
     * 'registers': Scope contains registers. Only a single 'registers' scope should be returned from a 'scopes'
     * request. etc.
     */
    public String presentationHint;
    /**
     * The variables of this scope can be retrieved by passing the value of variablesReference to the VariablesRequest.
     */
    public long variablesReference;
    /**
     * The number of named variables in this scope. The client can use this optional information to present the
     * variables in a paged UI and fetch them in chunks.
     */
    public Integer namedVariables;
    /**
     * The number of indexed variables in this scope. The client can use this optional information to present the
     * variables in a paged UI and fetch them in chunks.
     */
    public Integer indexedVariables;
    /** If true, the number of variables in this scope is large or expensive to retrieve. */
    public boolean expensive;
    /** Optional source for this scope. */
    public Source source;
    /** Optional start line of the range covered by this scope. */
    public Integer line;
    /** Optional start column of the range covered by this scope. */
    public Integer column;
    /** Optional end line of the range covered by this scope. */
    public Integer endLine;
    /** Optional end column of the range covered by this scope. */
    public Integer endColumn;
}
