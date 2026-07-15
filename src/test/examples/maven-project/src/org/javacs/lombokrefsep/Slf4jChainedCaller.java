package org.javacs.lombokrefsep;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class Slf4jChainedCaller {
    private void doStuff(HasChain obj) {
        obj.nonDefaultMethod("a", "b", "c").defaultVoidMethod("x", "y", "z");
    }
}
