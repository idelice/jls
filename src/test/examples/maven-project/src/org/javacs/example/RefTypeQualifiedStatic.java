package org.javacs.example;

class RefTypeQualifiedStatic {
    static int f;

    static void m() {}

    void run() {
        RefTypeQualifiedStatic.f = 1;
        RefTypeQualifiedStatic.m();
    }
}
