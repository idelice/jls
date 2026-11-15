package org.javacs.example;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;

public class ReferencesLocals {
    int a = 100;

    int multiDeclarator() {
        int a = 1, b = a + 1;
        b = b + 1;
        return a;
    }

    int forInit() {
        int n = 3, sum = 0;
        for (int i = 0; i < n; i++) {
            sum += i;
        }
        return sum;
    }

    void forEach(String[] names) {
        for (String s : names) {
            use(s);
            use(s + "!");
        }
    }

    void tryWithResources() throws IOException {
        try (var in = open()) {
            read(in);
            read(in);
        }
    }

    void catchParameter() {
        try {
            open();
        } catch (IOException e) {
            log(e);
            log(e);
        } catch (RuntimeException e) {
            log(e);
        }
    }

    void multiCatchParameter() {
        try {
            throwsBoth();
        } catch (IOException | SQLException e) {
            log(e);
            log(e);
        }
    }

    void capturedLocalInAnonymousClass() {
        String base = "base";
        use(base);
        Runnable r = new Runnable() {
            public void run() {
                use(base);
            }
        };
        r.run();
    }

    int patternVariableInstanceof(Object o) {
        if (o instanceof String s) {
            use(s);
            return s.length();
        }
        return 0;
    }

    void patternVariableAfterEarlyExit(Object o) {
        if (!(o instanceof String s)) {
            return;
        }
        use(s);
        use(s);
    }

    void varLocal() {
        var list = new ArrayList<String>();
        list.add("a");
        list.add("b");
        use(list.get(0));
    }

    void use(Object o) {}

    void read(Object o) {}

    void log(Throwable t) {}

    InputStream open() throws IOException {
        return null;
    }

    void throwsBoth() throws IOException, SQLException {}
}
