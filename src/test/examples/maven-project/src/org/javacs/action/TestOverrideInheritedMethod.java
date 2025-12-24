package org.javacs.action;

import java.util.function.Function;

class TestOverrideInheritedMethod implements Function<Integer, String> {

    @Override
    public String apply(Integer in) {
        return Integer.toString(in);
    }
}