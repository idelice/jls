package org.javacs.debug.proto;

/** CompletionItems are the suggestions returned from the CompletionsRequest. */
public class CompletionItem {
    /**
     * The label of this completion item. By default this is also the text that is inserted when selecting this
     * completion.
     */
    public String label;
    /** If text is not falsy then it is inserted instead of the label. */
    public String text;
    /**
     * The item's type. Typically the client uses this information to render the item in the UI with an icon. 'method' |
     * public * 'function' | 'constructor' | 'field' | 'variable' | 'class' | 'interface' | 'module' | 'property' |
     * 'unit' | 'value' | 'enum' | 'keyword' | 'snippet' | 'text' | 'color' | 'file' | 'reference' | 'customcolor'.
     */
    public String type;
    /**
     * This value determines the location (in the CompletionsRequest's 'text' attribute) where the completion text is
     * added. If missing the text is added at the location specified by the CompletionsRequest's 'column' attribute.
     */
    public Integer start;
    /**
     * This value determines how many characters are overwritten by the completion text. If missing the value 0 is
     * assumed which results in the completion text being inserted.
     */
    public Integer length;
}
