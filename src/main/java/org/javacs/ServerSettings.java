package org.javacs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;

/**
 * Pure JSON config parsing for project and client settings.
 * Reads .java-language-server.json from the workspace root and extracts
 * typed configuration values from the client-provided settings object.
 */
final class ServerSettings {
    private static final Logger LOG = Logger.getLogger("main");

    private final Path workspaceRoot;
    private final JsonObject settings;

    ServerSettings(Path workspaceRoot, JsonObject settings) {
        this.workspaceRoot = workspaceRoot;
        this.settings = settings;
    }

    Set<String> externalDependencies() {
        if (!settings.has("externalDependencies")) return Set.of();
        var array = settings.getAsJsonArray("externalDependencies");
        var strings = new HashSet<String>();
        for (var each : array) {
            strings.add(each.getAsString());
        }
        return strings;
    }

    Set<Path> classPath() {
        if (!settings.has("classPath")) return Set.of();
        var array = settings.getAsJsonArray("classPath");
        var paths = new HashSet<Path>();
        for (var each : array) {
            paths.add(Paths.get(each.getAsString()).toAbsolutePath());
        }
        return paths;
    }

    List<String> extraCompilerArgs() {
        var args = new ArrayList<String>();
        var file = projectSettings();
        if (file.has("extraCompilerArgs")) {
            for (var each : file.getAsJsonArray("extraCompilerArgs"))
                args.addAll(Arrays.asList(each.getAsString().trim().split("\\s+")));
        }
        if (settings.has("extraCompilerArgs")) {
            for (var each : settings.getAsJsonArray("extraCompilerArgs"))
                args.addAll(Arrays.asList(each.getAsString().trim().split("\\s+")));
        }
        return List.copyOf(args);
    }

    Set<Path> docPath() {
        if (!settings.has("docPath")) return Set.of();
        var array = settings.getAsJsonArray("docPath");
        var paths = new HashSet<Path>();
        for (var each : array) {
            paths.add(Paths.get(each.getAsString()).toAbsolutePath());
        }
        return paths;
    }

    Set<String> addExports() {
        var merged = new HashSet<String>();
        var file = projectSettings();
        if (file.has("addExports")) {
            for (var each : file.getAsJsonArray("addExports")) merged.add(each.getAsString());
        }
        if (settings.has("addExports")) {
            for (var each : settings.getAsJsonArray("addExports")) merged.add(each.getAsString());
        }
        return merged;
    }

    /** Read .java-language-server.json from the workspace root, or empty object if absent/invalid. */
    JsonObject projectSettings() {
        if (workspaceRoot == null) return new JsonObject();
        var file = workspaceRoot.resolve(".java-language-server.json");
        if (!Files.exists(file)) return new JsonObject();
        try {
            var text = Files.readString(file);
            var parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            LOG.warning("Failed to read .java-language-server.json: " + e.getMessage());
            return new JsonObject();
        }
    }

    /** Snapshot compiler-affecting settings for change detection. */
    static JsonObject compilerSettingsSnapshot(JsonObject source) {
        var snapshot = new JsonObject();
        if (source == null) {
            return snapshot;
        }
        copySettingIfPresent(source, snapshot, "externalDependencies");
        copySettingIfPresent(source, snapshot, "classPath");
        copySettingIfPresent(source, snapshot, "extraCompilerArgs");
        copySettingIfPresent(source, snapshot, "docPath");
        copySettingIfPresent(source, snapshot, "addExports");
        return snapshot;
    }

    private static void copySettingIfPresent(JsonObject source, JsonObject target, String key) {
        if (!source.has(key)) {
            return;
        }
        target.add(key, source.get(key).deepCopy());
    }
}
