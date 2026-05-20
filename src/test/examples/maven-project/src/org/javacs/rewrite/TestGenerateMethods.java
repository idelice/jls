package org.javacs.rewrite;

public class TestGenerateMethods {
    private String name;
    private int age;
    private final double salary;

    public TestGenerateMethods(double salary) {
        this.salary = salary;
    }

    public String getName() {
        return name;
    }
}
