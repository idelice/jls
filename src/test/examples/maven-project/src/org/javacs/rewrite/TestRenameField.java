package org.javacs.rewrite;

class TestRenameField {
    int foo = 1;

    void test() {
        var fooPlusOne = foo + 1;
    }
}

