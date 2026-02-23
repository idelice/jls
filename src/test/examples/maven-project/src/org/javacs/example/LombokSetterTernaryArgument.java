package org.javacs.example;

import lombok.Data;

@Data
class FooSetterTernary {
    private String something;
}

public class LombokSetterTernaryArgument {
    void test(String a) {
        var foo = new FooSetterTernary();
        foo.setSomething(a == null ? null : "asd");
    }
}
