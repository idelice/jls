package org.javacs.warn;

public class UnusedNonPrivate {
    public void usedPublicMethod() {
    }

    public void unusedPublicMethod() {
    }

    void unusedPackageMethod() {
    }

    protected void unusedProtectedMethod() {
    }

    public int unusedPublicField = 0;

    int usedPackageField = 0;
}
