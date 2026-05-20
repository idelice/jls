package org.javacs.lsp;

import com.google.gson.JsonElement;
import java.util.List;

public class ExecuteCommandParams {
    public String command;
    public List<JsonElement> arguments;
}
