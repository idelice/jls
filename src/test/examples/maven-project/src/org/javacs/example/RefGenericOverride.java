package org.javacs.example;

class RefGenericBase<T> {
    void m(T t) {}
}

class RefGenericSub extends RefGenericBase<String> {
    void m(String s) {}
}

class RefGenericUse {
    void run() {
        RefGenericSub b = new RefGenericSub();
        b.m("x");
    }
}
