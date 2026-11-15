package org.javacs.example;

import java.util.List;
import java.util.function.Function;

public class ReferencesParameters {
    void consume(String p) {
        use(p);
    }

    void other(String p) {
        use(p);
    }

    static class Ctor {
        int x;

        void bump() {
            x = 1;
        }

        Ctor(int x) {
            this.x = x;
        }

        int read() {
            return x;
        }
    }

    void varargs(String... xs) {
        use(xs[0]);
        use(xs[1]);
    }

    String lambdaExplicitParameter() {
        Function<String, String> f = (String s) -> {
            use(s);
            return s.trim();
        };
        return f.apply(" x ");
    }

    List<Integer> lambdaImplicitParameter(List<Integer> xs) {
        var plus = xs.stream().map(x -> x + 1).toList();
        var times = xs.stream().map(x -> x * 2).toList();
        return plus;
    }

    void use(Object o) {}
}
