package org.javacs.rewrite;

class TestExtractVariable {
    void methodWithMethodCall() {
        long size = Files.size(Paths.get("test.txt"));
    }

    void methodWithLiteral() {
        String greeting = "Hello World".toUpperCase();
    }

    void methodWithSimpleVar() {
        int x = 42;
        int y = x;
    }

    void methodWithExpression() {
        int a = 5;
        int b = 10;
        int c = a + b;
    }
}
