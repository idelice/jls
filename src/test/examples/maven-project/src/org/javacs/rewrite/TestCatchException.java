package org.javacs.rewrite;

import java.io.IOException;
import java.nio.file.*;

public class TestCatchException {
    public void methodWithCheckedException() throws IOException {
        Files.size(Paths.get("test.txt"));
    }

    public void methodWithMultipleStatements() {
        int a = 1;
        int b = 2;
        int c = a + b;
    }

    public void methodInsideTryCatch() {
        try {
            int x = 1;
        } catch (Exception e) {
            // already handled
        }
    }

    public int methodWithAssignment() {
        int result = 42;
        return result;
    }
}
