package org.javacs.example;

public class CompleteInMiddleOfMethodParamChain {
    void test(CompleteExpression envelope) {
        // Scenario: user deleted "create()" from:
        //   envelope.create().isVip()
        // leaving double-dot:
        //   envelope..isVip()
        // Cursor is between the two dots — receiver should be "envelope" (method param).
        boolean b = envelope..isVip();
    }
}
