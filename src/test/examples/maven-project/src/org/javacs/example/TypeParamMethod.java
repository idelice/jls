package org.javacs.example;

class TypeParamMethod<U> {
    U classField;

    <U> U id(U input) {
        U local = input;
        return local;
    }

    U getClassField() {
        return classField;
    }
}
