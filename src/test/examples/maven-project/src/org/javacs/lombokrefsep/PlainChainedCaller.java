package org.javacs.lombokrefsep;

class PlainChainedCaller {
    private void doStuff(HasChain obj) {
        obj.nonDefaultMethod("a", "b", "c").defaultVoidMethod("x", "y", "z");
    }
}
