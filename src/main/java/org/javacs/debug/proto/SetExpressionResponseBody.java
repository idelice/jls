package org.javacs.debug.proto;

public class SetExpressionResponseBody {
    /** The new value of the expression. */
    public String value;
    /** The optional type of the value. */
    public String type;
    /** Properties of a value that can be used to determine how to render the result in the UI. */
    public VariablePresentationHint presentationHint;
    /**
     * If variablesReference is > 0, the value is structured and its children can be retrieved by passing
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
