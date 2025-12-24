package org.javacs.debug.proto;

public class EvaluateResponseBody {
    /** The result of the evaluate request. */
    public String result;
    /** The optional type of the evaluate result. */
    public String type;
    /** Properties of a evaluate result that can be used to determine how to render the result in the UI. */
    public VariablePresentationHint presentationHint;
    /**
     * If variablesReference is > 0, the evaluate result is structured and its children can be retrieved by passing
     * variablesReference to the VariablesRequest.
     */
    public long variablesReference;
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
    /**
     * Memory reference to a location appropriate for this result. For pointer type eval results, this is generally a
     * reference to the memory address contained in the pointer.
     */
    public String memoryReference;
}
