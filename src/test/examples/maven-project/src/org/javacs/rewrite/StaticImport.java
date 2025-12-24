package org.javacs.rewrite;

import static java.util.Arrays.asList;

class MissingImport {
    void test() {
        var xs = asList(1, 2);
        xs.add("foo");
    }
}