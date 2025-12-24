package org.javacs.debug.proto;

/** Arguments for 'setVariable' request. */
public class SetVariableArguments {
    /** The reference of the variable container. */
    public long variablesReference;
    /** The name of the variable in the container. */
    public String name;
    /** The value of the variable. */
    public String value;
    /** Specifies details on how to format the response value. */
    public ValueFormat format;
}
