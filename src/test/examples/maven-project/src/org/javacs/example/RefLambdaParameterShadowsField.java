package org.javacs.example;

public class RefLambdaParameterShadowsField {
    private int x;

    RefIntUnary make() {
        return x -> {
            int y = x + this.x;
            return x + y;
        };
    }
}
