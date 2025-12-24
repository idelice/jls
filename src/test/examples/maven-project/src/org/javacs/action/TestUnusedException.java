package org.javacs.action;

class TestUnusedException {
    void test() {
        try {
            System.out.println("Hello, world!");
        } catch (RuntimeException e) {
            // Do nothing
        }
    }
}