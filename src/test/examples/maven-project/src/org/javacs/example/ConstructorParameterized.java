package org.javacs.example;

public class ConstructorParameterized {
    public ConstructorParameterized() {}

    public ConstructorParameterized(int value) {}

    void use() {
        new ConstructorParameterized();
        new ConstructorParameterized(1);
    }
}
