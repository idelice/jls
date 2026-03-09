package org.javacs;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.javacs.lsp.*;

public class Main {
    private static final Logger LOG = Logger.getLogger("main");

    public static void setRootFormat() {
        var root = Logger.getLogger("");

        for (var h : root.getHandlers()) {
            h.setFormatter(new LogFormat());
        }
    }

    public static void main(String[] args) {
        boolean quiet = Arrays.stream(args).anyMatch("--quiet"::equals);

        if (quiet) {
            LOG.setLevel(Level.OFF);
        }

        try {
            // JDK FINE logging for ProcessBuilder.start() is noisy and redundant with our own probe logs.
            Logger.getLogger("java.lang.ProcessBuilder").setLevel(Level.INFO);
            // Logger.getLogger("").addHandler(new FileHandler("javacs.%u.log", false));
            setRootFormat();

            LSP.connect(JavaLanguageServer::new, System.in, System.out);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, t.getMessage(), t);

            System.exit(1);
        }
    }
}
