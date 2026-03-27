package org.javacs.example;

interface InterfaceDefaultReference {
    default String fallbackDiscount(String segment) {
        return segment;
    }
}

class InterfaceDefaultReferenceImpl implements InterfaceDefaultReference {
    String use(String input) {
        return fallbackDiscount(input);
    }
}
