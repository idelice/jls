package org.javacs.action;

class TestConvertToStatement {
    void test() {
        int unusedLocal = makeInt();
    }

    int makeInt() {
        return 1;
    }
}