package org.javacs.example;

import java.util.function.Function;

public class PrivateMethodRefs {
    private Function<Long, String> factory;
    private String template;

    public void caller1(long timeout) {
        helper(factory.apply(timeout), "a", "b");
    }

    public void caller2() {
        helper(template, "x", "y");
    }

    private void helper(String a, String b, String c) {
    }
}
