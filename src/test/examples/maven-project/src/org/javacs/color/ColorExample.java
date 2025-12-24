package org.javacs.action.color;

public class ColorExample {
    int virtualField;

    void testVirtualField() {
        virtualField++;
    }

    void testMethod(int methodParameter) {
        methodParameter++;
    }

    static int staticField;

    void testStaticField() {
        staticField++;
    }
}