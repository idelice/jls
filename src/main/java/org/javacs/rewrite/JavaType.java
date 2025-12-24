package org.javacs.rewrite;

/** JavaType represents a potentially parameterized named type. */
public class JavaType {
    final String name;
    final JavaType[] parameters;

    public JavaType(String name, JavaType[] parameters) {
        this.name = name;
        this.parameters = parameters;
    }
}
