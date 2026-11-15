package org.javacs.example;

public class ReferencesStaticViaInstance {
    static void m() {}

    void call(ReferencesStaticViaInstance obj) {
        obj.m();
    }
}
