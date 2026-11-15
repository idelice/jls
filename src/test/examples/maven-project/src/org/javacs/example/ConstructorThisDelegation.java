package org.javacs.example;

public class ConstructorThisDelegation {
    public ConstructorThisDelegation(int value) {}

    public ConstructorThisDelegation() {
        this(1);
    }
}
