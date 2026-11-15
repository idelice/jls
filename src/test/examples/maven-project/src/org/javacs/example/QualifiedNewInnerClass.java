package org.javacs.example;

public class QualifiedNewInnerClass {
    public class Inner {
        public Inner() {}
    }

    void use() {
        new QualifiedNewInnerClass().new Inner();
    }
}
