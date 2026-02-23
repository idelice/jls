package org.javacs.example;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class LombokWrongArityFoo {
    public String doIt(String a, String b) {
        return a + b;
    }
}

@Slf4j
public class LombokWrongArityHover {
    private final LombokWrongArityFoo foo = new LombokWrongArityFoo();

    public void test(SomePojo somePojo) {
        foo.doIt(somePojo.getValue());
    }

    static class SomePojo {
        String getValue() {
            return "v";
        }
    }
}
