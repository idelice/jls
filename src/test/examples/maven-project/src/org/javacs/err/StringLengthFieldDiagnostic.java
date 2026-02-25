package org.javacs.err;

public class StringLengthFieldDiagnostic {
    void use(String token) {
        if (token.length > 1) {
            System.out.println(token);
        }
    }
}
