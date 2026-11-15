package org.javacs.example;

public class GenericAnonymousConstructor {
    static class Box<T> {
        Box(T value) {}
    }

    Object make() {
        return new Box<String>("x") {};
    }
}
