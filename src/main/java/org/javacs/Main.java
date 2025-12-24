package org.javacs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.LogManager;
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
            configureLogging();
            // Logger.getLogger("").addHandler(new FileHandler("javacs.%u.log", false));
            setRootFormat();

            LSP.connect(JavaLanguageServer::new, System.in, System.out);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, t.getMessage(), t);

            System.exit(1);
        }
    }

    private static void configureLogging() {
        if (System.getProperty("java.util.logging.config.file") != null) {
            return;
        }
        var configPath = Paths.get(System.getProperty("user.home"), ".config", "jls", "logging.properties");
        if (!Files.isRegularFile(configPath)) {
            return;
        }
        try (var in = Files.newInputStream(configPath)) {
            LogManager.getLogManager().readConfiguration(in);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to load logging config from " + configPath + ": " + e.getMessage(), e);
        }
    }
}
