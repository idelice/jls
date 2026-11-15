package org.javacs.example;

public class ConstructorOverloads {
    public ConstructorOverloads(int value) {}

    public ConstructorOverloads(String value) {}

    void use() {
        new ConstructorOverloads(1);
        new ConstructorOverloads("x");
    }
}
