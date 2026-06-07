package org.javacs.warn;

public class UnusedNonPrivateUser {
    void test() {
        var obj = new UnusedNonPrivate();
        obj.usedPublicMethod();
        int x = obj.usedPackageField;
    }
}
