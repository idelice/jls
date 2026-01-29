package org.javacs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Detects the target Java version from project build files.
 * Returns the version to use with javac's --release flag.
 */
public class JavaVersionDetector {
    private static final Logger LOG = Logger.getLogger("main");
    private static Integer runtimeVersion = null;

    /**
     * Get the Java version of the currently running JVM.
     * Returns the major version (e.g., 21 for Java 21.0.1).
     */
    public static int getRuntimeVersion() {
        if (runtimeVersion != null) {
            return runtimeVersion;
        }

        var version = System.getProperty("java.version");
        // Parse version string (could be "21", "21.0.1", "1.8.0_292", etc.)
        var parts = version.split("[._-]");
        if (parts.length > 0) {
            try {
                var major = Integer.parseInt(parts[0]);
                // Handle old versioning scheme (1.8.0 -> 8)
                if (major == 1 && parts.length > 1) {
                    runtimeVersion = Integer.parseInt(parts[1]);
                } else {
                    runtimeVersion = major;
                }
            } catch (NumberFormatException e) {
                LOG.warning("Could not parse Java version: " + version);
                runtimeVersion = 21; // Safe default
            }
        } else {
            runtimeVersion = 21; // Safe default
        }

        LOG.info("Detected runtime Java version: " + runtimeVersion);
        return runtimeVersion;
    }

    /**
     * Detect Java version from pom.xml, build.gradle, or gradle.properties.
     * Returns the major version (e.g., "8", "11", "17", "21") or empty if not found.
     */
    public static Optional<String> detectVersion(Path workspaceRoot) {
        // Try Maven pom.xml
        var pomPath = workspaceRoot.resolve("pom.xml");
        if (Files.exists(pomPath)) {
            var version = detectFromFile(pomPath,
                Pattern.compile("<java\\.version>(\\d+)</java\\.version>"),
                Pattern.compile("<maven\\.compiler\\.source>(\\d+)</maven\\.compiler\\.source>"),
                Pattern.compile("<maven\\.compiler\\.target>(\\d+)</maven\\.compiler\\.target>"),
                Pattern.compile("<source>(\\d+)</source>")
            );
            if (version.isPresent()) {
                LOG.info("Detected Java " + version.get() + " from pom.xml");
                return version;
            }
        }

        // Try Gradle build.gradle
        var gradlePath = workspaceRoot.resolve("build.gradle");
        if (Files.exists(gradlePath)) {
            var version = detectFromFile(gradlePath,
                Pattern.compile("sourceCompatibility\\s*[=']\\s*['\"]?([0-9]+)['\"]?"),
                Pattern.compile("targetCompatibility\\s*[=']\\s*['\"]?([0-9]+)['\"]?"),
                Pattern.compile("JavaVersion\\.VERSION_(\\d+)")
            );
            if (version.isPresent()) {
                LOG.info("Detected Java " + version.get() + " from build.gradle");
                return version;
            }
        }

        // Try gradle.properties
        var propsPath = workspaceRoot.resolve("gradle.properties");
        if (Files.exists(propsPath)) {
            var version = detectFromFile(propsPath,
                Pattern.compile("^sourceCompatibility\\s*=\\s*(\\d+)"),
                Pattern.compile("^targetCompatibility\\s*=\\s*(\\d+)")
            );
            if (version.isPresent()) {
                LOG.info("Detected Java " + version.get() + " from gradle.properties");
                return version;
            }
        }

        LOG.info("Could not detect Java version from build files");
        return Optional.empty();
    }

    private static Optional<String> detectFromFile(Path file, Pattern... patterns) {
        try {
            var content = Files.readString(file);
            for (var pattern : patterns) {
                var matcher = pattern.matcher(content);
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
            }
        } catch (Exception e) {
            LOG.fine("Failed to read " + file + ": " + e.getMessage());
        }
        return Optional.empty();
    }
}
