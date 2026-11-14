package org.javacs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

public class JsonHelper {
    public static final Gson GSON = new GsonBuilder().create();

    /** Read a string carried in an LSP {@code data} field, which may be a JSON primitive. */
    public static String asString(Object data) {
        if (data == null) return null;
        if (data instanceof JsonElement json) {
            return json.isJsonPrimitive() ? json.getAsString() : null;
        }
        return data.toString();
    }
}
