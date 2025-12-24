package org.javacs.example;

class GotoOverload {
    static int overloaded = 1;

    static void main() {
        overloaded = 10;
        overloaded(1);
        overloaded("1");
    }

    static void overloaded(int x) {

    }

    static void overloaded(String x) {

    }
}