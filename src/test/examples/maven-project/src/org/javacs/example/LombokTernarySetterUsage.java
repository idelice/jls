package org.javacs.example;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
class TernarySetterFoo {
    private String type;
    private String something = "a";
    private String otherHex = "b";
}

public class LombokTernarySetterUsage {
    void test(TernarySetterFoo foo, boolean condition) {
        foo.setType(condition ? foo.getSomething() : foo.getOtherHex());
    }
}
