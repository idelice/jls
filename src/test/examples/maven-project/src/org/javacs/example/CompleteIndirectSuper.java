package org.javacs.example;

class CompleteIndirectSuper extends CompleteIndirectSuper1 {
    static void test(CompleteIndirectSuper self) {
        self.
    }

    void selfMethod() { }
}

class CompleteIndirectSuper1 extends CompleteIndirectSuper2 {
    void super1Method() { }
}

class CompleteIndirectSuper2 {
    void super2Method() { }
}