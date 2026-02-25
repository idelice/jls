package org.javacs.example;

import java.util.logging.Logger;

public class FluentInterfaceChainCompletion {
    interface Proc {
        Proc prep(Foo foo, Bar context, Logger log);
        Proc prep2(Foo foo, Bar context, Logger log);
        Proc prep3(Foo foo, Bar context, Logger log);
    }

    static class Foo {}

    static class Bar {}

    void test(Proc processor, Foo foo, Bar context, Logger log) {
        processor
                .prep(foo, context, log)
                .
    }
}
