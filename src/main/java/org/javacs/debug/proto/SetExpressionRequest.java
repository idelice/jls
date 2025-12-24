package org.javacs.debug.proto;

/**
 * SetExpression request; value of command field is 'setExpression'. Evaluates the given 'value' expression and assigns
 * it to the 'expression' which must be a modifiable l-value. The expressions have access to any variables and arguments
 * that are in scope of the specified frame.
 */
public class SetExpressionRequest extends Request {
    public SetExpressionArguments arguments;
}
