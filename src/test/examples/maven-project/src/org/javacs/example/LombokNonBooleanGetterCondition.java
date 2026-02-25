package org.javacs.example;

import lombok.Data;

@Data
class NonBooleanGetterPoj {
    private String foo;
}

public class LombokNonBooleanGetterCondition {
    void test() {
        var value = new NonBooleanGetterPoj();
        if (value.getFoo()) {
        }
    }
}
