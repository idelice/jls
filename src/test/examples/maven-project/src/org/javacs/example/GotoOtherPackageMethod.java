package org.javacs.example;

import java.util.function.Consumer;

class GotoOtherPackageMethod {
    void gotoRun(Runnable r) {
        r.run();
    }

    void gotoAccept(Consumer<String> c) {
        c.accept("foo");
    }
}