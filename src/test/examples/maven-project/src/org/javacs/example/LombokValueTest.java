package org.javacs.example;

import lombok.Value;

@Value
class LombokValueTest {
    String value;
    int number;

    void use(LombokValueTest obj) {
        obj.getValue();
        obj.getNumber();
    }
}
