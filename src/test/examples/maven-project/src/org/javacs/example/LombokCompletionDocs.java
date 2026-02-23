package org.javacs.example;

import lombok.Data;

@Data
class LombokDocsPojo {
    @Deprecated
    private String foo;
}

public class LombokCompletionDocs {
    void test() {
        var value = new LombokDocsPojo();
        value.
    }
}
