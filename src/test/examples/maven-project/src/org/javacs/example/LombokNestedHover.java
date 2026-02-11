package org.javacs.example;

import lombok.Data;

public class LombokNestedHover {
    @Data
    static class Foo {
        private Bar bar;
    }

    @Data
    static class Bar {
        private Biz biz;
    }

    @Data
    static class Biz {
        private MyRec myRec;
        private long buhk;
    }

    record MyRec(String value) {
        String foo() {
            return value;
        }
    }

    void test(Foo d) {
        d.getBar().getBiz().getMyRec().foo();
    }
}
