package org.javacs.rewrite;

class TestRenameMethod {
    void test() {
        var fooPlusOne = foo() + 1;
    }

    int foo() {
        return 1;
    }
}

