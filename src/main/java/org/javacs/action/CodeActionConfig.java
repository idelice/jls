package org.javacs.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class CodeActionConfig {
    public final ConstructorConfig constructor;

    private CodeActionConfig(ConstructorConfig constructor) {
        this.constructor = constructor;
    }

    public static CodeActionConfig defaults() {
        return new CodeActionConfig(ConstructorConfig.defaults());
    }

    public static CodeActionConfig from(JsonObject settings) {
        if (settings == null) return defaults();
        var codeActions =
                settings.has("codeActions") && settings.get("codeActions").isJsonObject()
                        ? settings.getAsJsonObject("codeActions")
                        : new JsonObject();
        var ctor =
                codeActions.has("generateConstructor") && codeActions.get("generateConstructor").isJsonObject()
                        ? codeActions.getAsJsonObject("generateConstructor")
                        : new JsonObject();
        var include = compilePatterns(getStringList(ctor, "include"));
        return new CodeActionConfig(new ConstructorConfig(include));
    }

    private static List<String> getStringList(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) return List.of();
        JsonArray arr = obj.getAsJsonArray(key);
        var list = new ArrayList<String>(arr.size());
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonPrimitive()) continue;
            var prim = el.getAsJsonPrimitive();
            if (!prim.isString()) continue;
            var value = prim.getAsString();
            if (value != null && !value.isBlank()) list.add(value);
        }
        return list;
    }

    private static List<Pattern> compilePatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        var compiled = new ArrayList<Pattern>(patterns.size());
        for (var raw : patterns) {
            if (raw == null || raw.isBlank()) continue;
            try {
                compiled.add(Pattern.compile(raw));
            } catch (Exception e) {
                LOG.warning("Invalid constructor field pattern: " + raw);
            }
        }
        return compiled;
    }

    public static final class ConstructorConfig {
        public final List<Pattern> include;

        private ConstructorConfig(List<Pattern> include) {
            this.include = include == null ? List.of() : include;
        }

        static ConstructorConfig defaults() {
            return new ConstructorConfig(List.of());
        }

        public boolean isIncluded(String fieldName) {
            var name = Objects.toString(fieldName, "");
            if (!include.isEmpty()) {
                var allowed = false;
                for (var p : include) {
                    if (p.matcher(name).find()) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) return false;
            }
            return true;
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
