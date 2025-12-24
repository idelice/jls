package org.javacs.example;

import java.io.IOException;

class HoverThrows {
    void foo() throws IOException {
        throw new IOException();
    }

    void bar() {
        foo();
    }
}