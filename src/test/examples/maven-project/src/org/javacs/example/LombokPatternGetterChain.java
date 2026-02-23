package org.javacs.example;

import lombok.Data;

@Data
class SomePojoPatternGetterChain {
    private AnotherPojoPatternGetterChain anotherPojo;
}

@Data
class AnotherPojoPatternGetterChain {
    private BarPatternGetterChain bar;
}

@Data
class BarPatternGetterChain {
    private String value;
}

public class LombokPatternGetterChain {
    void test(Object obj) {
        if (obj instanceof SomePojoPatternGetterChain somepojo) {
            var x = somepojo.getAnotherPojo().getBar();
            x.getValue();
        }
    }
}
