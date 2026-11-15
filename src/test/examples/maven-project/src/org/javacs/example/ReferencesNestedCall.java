package org.javacs.example;

public class ReferencesNestedCall {
    private void helper() {}

    class Inner {
        void call() {
            helper();
        }
    }

    Runnable anonymous = new Runnable() {
        public void run() {
            helper();
        }
    };
}
