package org.javacs.example;

class GotoMismatchedMethod {
    void test() {
        method(1.0);
    }
    void method(String x) { }
    void method(int x) { }
}