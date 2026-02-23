package org.javacs.example;

import lombok.Data;

@Data
class LombokVarSplitFlowFoo {
    private String asd;
}

public class LombokVarSplitFlow {
    void test() {
        var foo = new LombokVarSplitFlowFoo();
        var b = foo.getAsd();
        var sp = b.split("_");
        if (sp.length() > 1) {
            System.out.println(sp[0]);
        }
    }
}
