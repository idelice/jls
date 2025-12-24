package org.javacs.debug.proto;

/** Arguments for 'variables' request. */
public class VariablesArguments {
    /** The Variable reference. */
    public long variablesReference;
    /**
     * Optional filter to limit the child variables to either named or indexed. If ommited, both types are fetched.
     * 'indexed' | 'named'.
     */
    public String filter;
    /** The index of the first variable to return; if omitted children start at 0. */
    public Integer start;
    /** The number of variables to return. If count is missing or 0, all variables are returned. */
    public Integer count;
    /** Specifies details on how to format the Variable values. */
    public ValueFormat format;
}
