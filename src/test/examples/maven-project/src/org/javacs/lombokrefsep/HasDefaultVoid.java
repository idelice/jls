package org.javacs.lombokrefsep;

interface HasDefaultVoid {
    default void defaultVoidMethod(String a, String b, String c) {}
}

interface HasChain {
    HasDefaultVoid nonDefaultMethod(String a, String b, String c);
}
