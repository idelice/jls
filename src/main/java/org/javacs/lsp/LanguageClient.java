package org.javacs.lsp;

import com.google.gson.JsonElement;

public interface LanguageClient {
    void publishDiagnostics(PublishDiagnosticsParams params);

    void showMessage(ShowMessageParams params);

    void registerCapability(String method, JsonElement options);

    void customNotification(String method, JsonElement params);
}
