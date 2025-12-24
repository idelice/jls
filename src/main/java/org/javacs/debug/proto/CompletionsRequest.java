package org.javacs.debug.proto;

/**
 * Completions request; value of command field is 'completions'. Returns a list of possible completions for a given
 * caret position and text. The CompletionsRequest may only be called if the 'supportsCompletionsRequest' capability
 * exists and is true.
 */
public class CompletionsRequest extends Request {
    public CompletionsArguments arguments;
}
