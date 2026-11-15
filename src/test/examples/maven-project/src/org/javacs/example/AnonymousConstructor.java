package org.javacs.example;

public class AnonymousConstructor {
    public AnonymousConstructor() {}

    void use() {
        new AnonymousConstructor() {};
        new AnonymousConstructor();
    }
}
