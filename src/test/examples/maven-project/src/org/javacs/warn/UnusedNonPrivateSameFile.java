package org.javacs.warn;

public class UnusedNonPrivateSameFile {
    public void callerMethod() {
        helperMethod();
        int x = helperField;
    }

    public void helperMethod() {
    }

    public int helperField = 42;

    public void trulyUnusedMethod() {
    }
}
