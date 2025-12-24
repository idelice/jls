package org.javacs.example;

public class ErrorInDependency {
    public String test() {
        return (new UndefinedSymbol()).test();
    }
}