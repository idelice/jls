package org.javacs.example;

public class ReferencesOverloadWidening {
    void m(long a) {}

    void m(Integer a) {}

    void callWidening() {
        m(1);
    }

    void callBoxing() {
        m(Integer.valueOf(1));
    }
}
