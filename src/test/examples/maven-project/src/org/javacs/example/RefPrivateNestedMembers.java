package org.javacs.example;

public class RefPrivateNestedMembers {
    private int f;

    private void m() {}

    class Inner {
        void use() {
            f++;
            m();
        }
    }
}
