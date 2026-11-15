package org.javacs.example;

public class RefNestedTypeOuter {
    static class Inner {}

    Inner member;

    org.javacs.other.Inner top;

    void use() {
        Inner local = null;
        org.javacs.other.Inner other = null;
    }
}
