package org.javacs.example;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
enum LombokEnumBar {
    A("A"),
    B("B");

    private final String code;
}

@Getter
@AllArgsConstructor
enum LombokEnumFoo {
    A(LombokEnumBar.A),
    B(LombokEnumBar.B);

    private final LombokEnumBar bar;

    public static String getBy(LombokEnumBar bar) {
        for (LombokEnumFoo f : values()) {
            if (f.getBar() == bar) {
                return f.name();
            }
        }
        return null;
    }
}

public class LombokEnumGetterValuesLoop {
    String test() {
        return LombokEnumFoo.getBy(LombokEnumBar.A);
    }
}
