package org.javacs.err;

import lombok.Data;

@Data
class LombokFieldTypeChangeFoo {
    private String amount;
}

public class LombokFieldTypeChange {
    void test() {
        var foo = new LombokFieldTypeChangeFoo();
        foo.setAmount("1");
    }
}
