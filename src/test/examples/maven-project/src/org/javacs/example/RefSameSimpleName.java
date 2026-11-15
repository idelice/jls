package org.javacs.example;

public class RefSameSimpleName {
    a.C left;

    b.C right;

    void use() {
        a.C localA = null;
        b.C localB = null;
    }
}
