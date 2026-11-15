package org.javacs.example;

class RefMethodReferenceOverload {
    static int m(int n) {
        return n;
    }

    static int m(long n) {
        return (int) n;
    }

    RefIntUnary op() {
        return RefMethodReferenceOverload::m;
    }
}
