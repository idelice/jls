package org.javacs.example;

import lombok.Data;

@Data
class LombokFieldReferences {
    private String bar;

    void use(LombokFieldReferences foo) {
        foo.getBar();
        foo.setBar("x");
        foo.bar = "y";
        var value = foo.bar;
    }
}
