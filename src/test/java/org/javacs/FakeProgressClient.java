package org.javacs;

import com.google.gson.JsonElement;
import org.javacs.lsp.*;

class FakeProgressClient implements LanguageClient {
    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {}

    @Override
    public void showMessage(ShowMessageParams params) {}

    @Override
    public void registerCapability(String method, JsonElement options) {}

    @Override
    public void customNotification(String method, JsonElement params) {}

    @Override
    public void workDoneProgressCreate(Object token) {}

    @Override
    public void workDoneProgressNotify(ProgressParams params) {}
}
