package org.javacs.debug.proto;

public class SetVariableResponseBody {
    /** The new value of the variable. */
    public String value;
    /** The type of the new value. Typically shown in the UI when hovering over the value. */
    public String type;
    /**
     * If variablesReference is > 0, the new value is structured and its children can be retrieved by passing
     * variablesReference to the VariablesRequest.
     */
    public Integer variablesReference;
    /**
     * The number of named child variables. The client can use this optional information to present the variables in a
     * paged UI and fetch them in chunks.
     */
    public Integer namedVariables;
    /**
     * The number of indexed child variables. The client can use this optional information to present the variables in a
     * paged UI and fetch them in chunks.
     */
    public Integer indexedVariables;
}
