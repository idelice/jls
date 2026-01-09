package org.javacs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

final class LombokSourceCache {
    private static final Logger LOG = Logger.getLogger("main");

    private final Path lombokJar;
    private final Path javaExe;

    LombokSourceCache(Path lombokJar) {
        this.lombokJar = lombokJar;
        this.javaExe = Paths.get(System.getProperty("java.home"), "bin", "java");
    }

    synchronized SourceFileObject generated(Path source) {
        if (!Files.isRegularFile(lombokJar)) {
            LOG.fine("Lombok jar not found for delombok: " + lombokJar);
            return null;
        }
        if (source == null) return null;
        var cachePath = cachePath(source);
        try {
            var sourceModified = FileStore.modified(source);
            if (sourceModified == null) {
                sourceModified = Instant.now();
            }
            if (needsGeneration(sourceModified, cachePath)) {
                runDelombok(source, cachePath);
            }
            if (!Files.exists(cachePath)) {
                return null;
            }
            var contents = Files.readString(cachePath, StandardCharsets.UTF_8);
            var generatedModified = Files.getLastModifiedTime(cachePath).toInstant();
            return new SourceFileObject(source, contents, generatedModified);
        } catch (IOException e) {
            LOG.fine("Failed to read Lombok cache for " + source + ": " + e.getMessage());
            return null;
        }
    }

    private boolean needsGeneration(Instant sourceModified, Path cachePath) throws IOException {
        if (!Files.exists(cachePath)) {
            return true;
        }
        var generatedModified = Files.getLastModifiedTime(cachePath).toInstant();
        return sourceModified.isAfter(generatedModified);
    }

    private void runDelombok(Path source, Path cachePath) {
        try {
            Files.createDirectories(cachePath.getParent());
            var cmd = new ArrayList<String>();
            cmd.add(javaExe.toString());
            cmd.add("-jar");
            cmd.add(lombokJar.toString());
            cmd.add("delombok");
            cmd.add("-p");
            cmd.add(source.toString());
            cmd.add("--encoding");
            cmd.add("UTF-8");
            var builder = new java.lang.ProcessBuilder(cmd);
            builder.redirectErrorStream(true);
            var process = builder.start();
            var contents = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var exit = process.waitFor();
            if (exit != 0) {
                LOG.fine("Delombok failed (" + exit + ") for " + source + ": " + contents);
                return;
            }
            Files.writeString(cachePath, contents, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.fine("Delombok execution failed for " + source + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.fine("Delombok interrupted for " + source);
        }
    }

    private Path cachePath(Path source) {
        var root = FileStore.sourceRoot(source);
        Path relative;
        if (root != null) {
            var rootLabel = root.getFileName();
            if (rootLabel == null) {
                rootLabel = Paths.get("root");
            }
            relative = rootLabel.resolve(root.relativize(source));
        } else {
            var normalized = source.toAbsolutePath().normalize();
            var hash = Integer.toHexString(normalized.hashCode());
            relative = Paths.get("external").resolve(hash).resolve(normalized.getFileName());
        }
        return CacheConfig.cacheDir().resolve("lombok").resolve(relative);
    }
}
