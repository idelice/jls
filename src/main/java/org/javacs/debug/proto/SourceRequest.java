package org.javacs.debug.proto;

/**
 * Source request; value of command field is 'source'. The request retrieves the source code for a given source
 * reference.
 */
public class SourceRequest extends Request {
    public SourceArguments arguments;
}
