package org.javacs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Manages caching of Maven/Gradle dependency resolution results to speed up startup. Cache is stored
 * under ~/.cache/jls/ organized by workspace directory name.
 */
class CacheManager {
    private static final Logger LOG = Logger.getLogger("main");
    private static final Path CACHE_ROOT = Paths.get(System.getProperty("user.home"), ".cache", "jls");
    private static final String CLASSPATH_CACHE = "classpath.cache";
    private static final String DOCPATH_CACHE = "docpath.cache";
    private static final String METADATA_FILE = "metadata.json";

    /** Load cached classpath and docpath if valid */
    Optional<CachedPaths> loadCache(Path workspaceRoot, Collection<String> externalDependencies) {
        try {
            var workspaceDirName = getWorkspaceCacheDirName(workspaceRoot);
            var cacheDir = CACHE_ROOT.resolve(workspaceDirName);

            if (!Files.exists(cacheDir)) {
                return Optional.empty();
            }

            var metadataPath = cacheDir.resolve(METADATA_FILE);
            if (!Files.exists(metadataPath)) {
                return Optional.empty();
            }

            var metadata = readMetadata(metadataPath);
            if (metadata == null) {
                return Optional.empty();
            }

            // Validate cache freshness
            if (!isCacheValid(workspaceRoot, metadata, externalDependencies)) {
                return Optional.empty();
            }

            var classPathFile = cacheDir.resolve(CLASSPATH_CACHE);
            var docPathFile = cacheDir.resolve(DOCPATH_CACHE);

            if (!Files.exists(classPathFile) || !Files.exists(docPathFile)) {
                return Optional.empty();
            }

            var classPath = readPathsFromFile(classPathFile);
            var docPath = readPathsFromFile(docPathFile);

            LOG.info("Cache hit for workspace " + workspaceRoot + " (" + workspaceDirName + ")");
            return Optional.of(new CachedPaths(classPath, docPath));

        } catch (Exception e) {
            LOG.warning("Failed to load cache: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Save resolved classpath and docpath to cache */
    void saveCache(Path workspaceRoot, Collection<String> externalDependencies, Set<Path> classPath,
            Set<Path> docPath) {
        try {
            var workspaceDirName = getWorkspaceCacheDirName(workspaceRoot);
            var cacheDir = CACHE_ROOT.resolve(workspaceDirName);

            // Create cache directory if it doesn't exist
            Files.createDirectories(cacheDir);

            // Write paths to cache files
            var classPathFile = cacheDir.resolve(CLASSPATH_CACHE);
            var docPathFile = cacheDir.resolve(DOCPATH_CACHE);

            writePathsToFile(classPathFile, classPath);
            writePathsToFile(docPathFile, docPath);

            // Write metadata
            var metadata = new CacheMetadata(workspaceRoot, externalDependencies);
            writeMetadata(cacheDir.resolve(METADATA_FILE), metadata);

            LOG.info("Cached dependencies for workspace " + workspaceRoot + " (" + workspaceDirName + ")");

        } catch (Exception e) {
            LOG.warning("Failed to save cache: " + e.getMessage());
            // Fail gracefully - not having cache is not critical
        }
    }

    /** Clear cache for a specific workspace */
    void clearCache(Path workspaceRoot) {
        try {
            var workspaceDirName = getWorkspaceCacheDirName(workspaceRoot);
            var cacheDir = CACHE_ROOT.resolve(workspaceDirName);

            if (Files.exists(cacheDir)) {
                deleteDirectory(cacheDir);
                LOG.info("Cleared cache for workspace " + workspaceRoot);
            }
        } catch (Exception e) {
            LOG.warning("Failed to clear cache: " + e.getMessage());
        }
    }

    /**
     * Generate cache directory name from workspace root. Uses format: basename-<8-char-hash>
     * Example: projectA-a55e4eac, projectB-f2b9d3e1
     *
     * The hash is included to avoid collisions when different workspaces have the same basename.
     */
    private static String getWorkspaceCacheDirName(Path workspaceRoot) throws NoSuchAlgorithmException {
        var basename = workspaceRoot.getFileName().toString();
        var pathHash = hashWorkspacePath(workspaceRoot);
        return basename + "-" + pathHash;
    }

    /** Generate SHA-256 hash of workspace root path (first 8 chars) */
    private static String hashWorkspacePath(Path workspaceRoot) throws NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256");
        var hash = digest.digest(workspaceRoot.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
        var hex = new StringBuilder();
        for (var b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.substring(0, Math.min(8, hex.length()));
    }

    /** Check if cache is still valid based on build file modification times */
    private boolean isCacheValid(Path workspaceRoot, CacheMetadata metadata,
            Collection<String> externalDependencies) {
        // Check if external dependencies changed
        var externalDepsHash = hashExternalDependencies(externalDependencies);
        if (!metadata.externalDepsHash.equals(externalDepsHash)) {
            LOG.info("Cache invalid: external dependencies changed");
            return false;
        }

        // Check build file modification times
        var buildFiles = new String[] {"pom.xml", "build.gradle", "WORKSPACE", "BUILD"};
        for (var buildFile : buildFiles) {
            var path = workspaceRoot.resolve(buildFile);
            if (Files.exists(path)) {
                try {
                    var lastModified = Files.getLastModifiedTime(path).toMillis();
                    var cachedTime = metadata.buildFiles.getOrDefault(buildFile, 0L);

                    if (lastModified > cachedTime) {
                        LOG.info("Cache invalid: " + buildFile + " was modified");
                        return false;
                    }
                } catch (IOException e) {
                    LOG.warning("Failed to check build file modification time: " + e.getMessage());
                    return false;
                }
            }
        }

        return true;
    }

    /** Generate hash of external dependencies list */
    private static String hashExternalDependencies(Collection<String> externalDependencies) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var sorted = new ArrayList<>(externalDependencies);
            Collections.sort(sorted);
            var content = String.join("|", sorted);
            var hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (var b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** Read paths from cache file (one path per line) */
    private static Set<Path> readPathsFromFile(Path file) throws IOException {
        var paths = new HashSet<Path>();
        for (var line : Files.readAllLines(file)) {
            line = line.trim();
            if (!line.isEmpty()) {
                paths.add(Paths.get(line));
            }
        }
        return paths;
    }

    /** Write paths to cache file (one path per line) */
    private static void writePathsToFile(Path file, Set<Path> paths) throws IOException {
        var lines = new ArrayList<String>();
        for (var path : paths) {
            lines.add(path.toString());
        }
        Collections.sort(lines); // Sort for consistency
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** Read cache metadata from JSON file */
    private static CacheMetadata readMetadata(Path file) throws Exception {
        var json = Files.readString(file);
        var obj = JsonHelper.GSON.fromJson(json, com.google.gson.JsonObject.class);

        var buildFiles = new HashMap<String, Long>();
        if (obj.has("buildFiles")) {
            var buildFilesObj = obj.getAsJsonObject("buildFiles");
            for (var key : buildFilesObj.keySet()) {
                buildFiles.put(key, buildFilesObj.get(key).getAsLong());
            }
        }

        return new CacheMetadata(obj.get("workspaceRoot").getAsString(),
                obj.get("externalDepsHash").getAsString(), buildFiles,
                obj.get("createdAt").getAsLong());
    }

    /** Write cache metadata to JSON file */
    private static void writeMetadata(Path file, CacheMetadata metadata) throws IOException {
        var obj = new com.google.gson.JsonObject();
        obj.addProperty("workspaceRoot", metadata.workspaceRoot);
        obj.addProperty("externalDepsHash", metadata.externalDepsHash);

        var buildFilesObj = new com.google.gson.JsonObject();
        for (var entry : metadata.buildFiles.entrySet()) {
            buildFilesObj.addProperty(entry.getKey(), entry.getValue());
        }
        obj.add("buildFiles", buildFilesObj);

        obj.addProperty("createdAt", metadata.createdAt);
        obj.addProperty("version", "1.0");

        Files.writeString(file, JsonHelper.GSON.toJson(obj));
    }

    /** Delete directory recursively */
    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (var path : stream.toArray(Path[]::new)) {
                if (Files.isDirectory(path)) {
                    deleteDirectory(path);
                } else {
                    Files.delete(path);
                }
            }
        }
        Files.delete(dir);
    }

    /** Container for cached paths */
    static class CachedPaths {
        final Set<Path> classPath;
        final Set<Path> docPath;

        CachedPaths(Set<Path> classPath, Set<Path> docPath) {
            this.classPath = classPath;
            this.docPath = docPath;
        }
    }

    /** Metadata about cached results */
    private static class CacheMetadata {
        final String workspaceRoot;
        final String externalDepsHash;
        final Map<String, Long> buildFiles;
        final long createdAt;

        CacheMetadata(Path workspaceRoot, Collection<String> externalDependencies) {
            this.workspaceRoot = workspaceRoot.toString();
            this.externalDepsHash = hashExternalDependencies(externalDependencies);
            this.buildFiles = captureBuildFileTimes(workspaceRoot);
            this.createdAt = Instant.now().toEpochMilli();
        }

        CacheMetadata(String workspaceRoot, String externalDepsHash, Map<String, Long> buildFiles,
                long createdAt) {
            this.workspaceRoot = workspaceRoot;
            this.externalDepsHash = externalDepsHash;
            this.buildFiles = buildFiles;
            this.createdAt = createdAt;
        }

        /** Capture current modification times of build files */
        private static Map<String, Long> captureBuildFileTimes(Path workspaceRoot) {
            var times = new HashMap<String, Long>();
            var buildFiles = new String[] {"pom.xml", "build.gradle", "WORKSPACE", "BUILD"};
            for (var buildFile : buildFiles) {
                var path = workspaceRoot.resolve(buildFile);
                try {
                    if (Files.exists(path)) {
                        var lastModified = Files.getLastModifiedTime(path).toMillis();
                        times.put(buildFile, lastModified);
                    }
                } catch (IOException e) {
                    LOG.warning("Failed to get modification time for " + buildFile + ": "
                            + e.getMessage());
                }
            }
            return times;
        }
    }
}
