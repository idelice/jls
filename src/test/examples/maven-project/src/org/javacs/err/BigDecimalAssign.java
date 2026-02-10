package org.javacs.err;

import java.math.BigDecimal;

public class BigDecimalAssign {
    String tip;

    void test(BigDecimal amount) {
        tip = amount.setScale(2);
    }
}
