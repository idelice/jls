package org.javacs.example;

class TypeParamBox<T> {
    T value;

    T get(T input) {
        T local = input;
        return local;
    }
}
