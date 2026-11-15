package org.javacs.example;

public class ReferencesPrivate {
    private int counter;
    private String name;

    private void increment() {
        counter++;
    }

    void use() {
        increment();
        counter = 1;
        name = "x";
    }
}
