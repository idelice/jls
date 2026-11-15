package org.javacs.example;

public class ReferencesFieldIncrement {
    private int count;

    void run() {
        count++;
        count += 2;
        count = 0;
        use(count);
    }

    void use(int n) {}
}
