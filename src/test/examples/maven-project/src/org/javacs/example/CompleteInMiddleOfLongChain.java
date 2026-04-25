package org.javacs.example;

public class CompleteInMiddleOfLongChain {
    void test() {
        // Scenario: user deleted "someMethod()" from a longer chain:
        //   CompleteExpression.create().someMethod().instanceMethod()
        // leaving double-dot with more chain after:
        //   CompleteExpression.create()..instanceMethod()
        // Cursor is between the two dots (at second dot = col 38+)
        CompleteExpression.create()..instanceMethod();
    }
}
