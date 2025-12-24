package org.javacs.warn;

import java.io.IOException;

class NotThrown {
    void notThrown() throws IOException { }

    void isThrown() throws IOException {
        notThrown();
    }
}