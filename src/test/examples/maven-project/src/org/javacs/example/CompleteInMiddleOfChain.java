package org.javacs.example;

public class CompleteInMiddleOfChain {
    void test() {
        // Scenario: user deleted a method name, leaving two consecutive dots
        // Cursor is at position of second dot (between the two dots)
        // Line: "        CompleteExpression obj = CompleteExpression.create()..toString();"
        // After deleting "create()", cursor is between the two dots:
        CompleteExpression obj = CompleteExpression.create()..toString();
    }
}
