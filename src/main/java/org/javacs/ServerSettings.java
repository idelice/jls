package org.javacs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;

/** Startup configuration. Parsed once, then shared by the server and its providers. */
final class ServerSettings {
    private static final Logger LOG = Logger.getLogger("main");

    private boolean initialized;
    private boolean richFormatter = true;
    private boolean inlayHints;
    private Set<String> externalDependencies = Set.of();
    private Set<Path> classPath = Set.of();
    private Set<Path> docPath = Set.of();
    private List<String> extraCompilerArgs = List.of();
    private Set<String> addExports = Set.of();

    void initialize(Path workspaceRoot, JsonObject client) {
        if (initialized) return;
        var project = readProjectSettings(workspaceRoot);
        richFormatter = !client.has("richFormatter") || client.get("richFormatter").getAsBoolean();
        inlayHints = client.has("inlayHints") && client.get("inlayHints").getAsBoolean();
        externalDependencies = strings(client, "externalDependencies");
        classPath = paths(client, "classPath");
        docPath = paths(client, "docPath");
        var args = new ArrayList<String>();
        addCompilerArgs(args, project);
        addCompilerArgs(args, client);
        extraCompilerArgs = List.copyOf(args);
        var exports = new HashSet<>(strings(project, "addExports"));
        exports.addAll(strings(client, "addExports"));
        addExports = Set.copyOf(exports);
        initialized = true;
    }

    boolean richFormatter() { return richFormatter; }
    boolean inlayHints() { return inlayHints; }
    Set<String> externalDependencies() { return externalDependencies; }
    Set<Path> classPath() { return classPath; }
    Set<Path> docPath() { return docPath; }
    List<String> extraCompilerArgs() { return extraCompilerArgs; }
    Set<String> addExports() { return addExports; }

    private static Set<String> strings(JsonObject source, String key) {
        if (!source.has(key)) return Set.of();
        var result = new HashSet<String>();
        for (var value : source.getAsJsonArray(key)) result.add(value.getAsString());
        return Set.copyOf(result);
    }

    private static Set<Path> paths(JsonObject source, String key) {
        var result = new HashSet<Path>();
        for (var value : strings(source, key)) result.add(Paths.get(value).toAbsolutePath());
        return Set.copyOf(result);
    }

    private static void addCompilerArgs(List<String> args, JsonObject source) {
        if (!source.has("extraCompilerArgs")) return;
        for (var value : source.getAsJsonArray("extraCompilerArgs")) {
            args.addAll(Arrays.asList(value.getAsString().trim().split("\\s+")));
        }
    }

    private static JsonObject readProjectSettings(Path workspaceRoot) {
        var file = workspaceRoot.resolve(".java-language-server.json");
        if (!Files.exists(file)) return new JsonObject();
        try {
            var parsed = JsonParser.parseString(Files.readString(file));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            LOG.warning("Failed to read .java-language-server.json: " + e.getMessage());
            return new JsonObject();
        }
    }
}
