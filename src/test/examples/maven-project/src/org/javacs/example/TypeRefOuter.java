package org.javacs.example;

class TypeRefOuter {
    static class Inner {}

    Inner field;

    void use() {
        TypeRefOuter.Inner qualified = null;
        Inner simple = null;
    }
}
