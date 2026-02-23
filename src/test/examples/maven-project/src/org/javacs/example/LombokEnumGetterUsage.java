package org.javacs.example;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
enum LombokEnumGetterFoo {
    A(new Bar("ASD"));

    private final Bar bar;

    public static LombokEnumGetterFoo getBy(String ignored) {
        return A;
    }

    static class Bar {
        final String value;

        Bar(String value) {
            this.value = value;
        }
    }
}

public class LombokEnumGetterUsage {
    void test() {
        LombokEnumGetterFoo result = LombokEnumGetterFoo.getBy("x");
        result.getBar();
    }
}
