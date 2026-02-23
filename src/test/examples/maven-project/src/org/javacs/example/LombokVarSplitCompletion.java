package org.javacs.example;

import lombok.Data;

@Data
class LombokVarSplitCompletionFoo {
    private String asd;
}

public class LombokVarSplitCompletion {
    void test() {
        var foo = new LombokVarSplitCompletionFoo();
        var b = foo.getAsd();
        b.
    }
}
