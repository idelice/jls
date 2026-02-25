package org.javacs.err;

import java.io.IOException;

public class CheckedExceptionDiagnostic {
    void willThrow() throws IOException {
        throw new IOException("boom");
    }

    void use() {
        willThrow();
    }
}
