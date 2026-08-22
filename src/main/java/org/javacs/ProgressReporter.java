package org.javacs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.UUID;
import org.javacs.lsp.LanguageClient;

/**
 * Encapsulates LSP work-done-progress reporting (begin/report/end) so that callers
 * only need a token and message string.
 */
class ProgressReporter {
    private final LanguageClient client;
    private final boolean supported;

    ProgressReporter(LanguageClient client, boolean supported) {
        this.client = client;
        this.supported = supported;
    }

    static boolean supportsWorkDoneProgress(JsonElement capabilities) {
        if (capabilities == null || !capabilities.isJsonObject()) {
            return false;
        }
        var root = capabilities.getAsJsonObject();
        if (!root.has("window") || !root.get("window").isJsonObject()) {
            return false;
        }
        var window = root.getAsJsonObject("window");
        return window.has("workDoneProgress") && window.get("workDoneProgress").getAsBoolean();
    }

    String begin(String title, String message) {
        if (!supported) {
            return null;
        }
        var token = UUID.randomUUID().toString();
        var create = new JsonObject();
        create.addProperty("token", token);
        client.sendRequest("window/workDoneProgress/create", create);

        var value = new JsonObject();
        value.addProperty("kind", "begin");
        value.addProperty("title", title);
        value.addProperty("message", message);
        value.addProperty("cancellable", false);
        var progress = new JsonObject();
        progress.addProperty("token", token);
        progress.add("value", value);
        client.customNotification("$/progress", progress);
        return token;
    }

    void report(String token, String message) {
        if (token == null) {
            return;
        }
        var value = new JsonObject();
        value.addProperty("kind", "report");
        value.addProperty("message", message);
        value.addProperty("cancellable", false);
        var progress = new JsonObject();
        progress.addProperty("token", token);
        progress.add("value", value);
        client.customNotification("$/progress", progress);
    }

    void end(String token, String message) {
        if (token == null) {
            return;
        }
        var value = new JsonObject();
        value.addProperty("kind", "end");
        if (message != null && !message.isBlank()) {
            value.addProperty("message", message);
        }
        var progress = new JsonObject();
        progress.addProperty("token", token);
        progress.add("value", value);
        client.customNotification("$/progress", progress);
    }
}
