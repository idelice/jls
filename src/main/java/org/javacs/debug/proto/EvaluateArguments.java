package org.javacs.debug.proto;

/** Arguments for 'evaluate' request. */
public class EvaluateArguments {
    /** The expression to evaluate. */
    public String expression;
    /**
     * Evaluate the expression in the scope of this stack frame. If not specified, the expression is evaluated in the
     * global scope.
     */
    public Integer frameId;
    /**
     * The context in which the evaluate request is run. Values: 'watch': evaluate is run in a watch. 'repl': evaluate
     * is run from REPL console. 'hover': evaluate is run from a data hover. etc.
     */
    public String context;
    /** Specifies details on how to format the Evaluate result. */
    public ValueFormat format;
}
