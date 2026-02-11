package org.javacs.example;

import lombok.Data;

public class LombokCompletionFieldChange {
    @Data
    static class Foo {
        private String name;
    }

    void test() {
        var foo = new Foo();
        foo.se
    }
}
