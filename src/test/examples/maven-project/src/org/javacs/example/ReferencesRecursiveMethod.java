package org.javacs.example;

public class ReferencesRecursiveMethod {
    void m(int n) {
        if (n > 0) {
            m(n - 1);
        }
    }
}
