package org.javacs.debug.proto;

/**
 * Variables request; value of command field is 'variables'. Retrieves all child variables for the given variable
 * reference. An optional filter can be used to limit the fetched children to either named or indexed children.
 */
public class VariablesRequest extends Request {
    public VariablesArguments arguments;
}
