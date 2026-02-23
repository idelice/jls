package org.javacs.example;

import lombok.Data;

@Data
class ThisPoj {
    private String foo;

    void something() {
        this.setFoo("asd");
    }
}

public class LombokThisSetterAndCondition {
    void test() {
        var ksk = new ThisPoj();
        ksk.
        if (ksk.getFoo()) {
        }
    }
}
