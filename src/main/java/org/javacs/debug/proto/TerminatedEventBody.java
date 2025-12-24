package org.javacs.debug.proto;

import com.google.gson.JsonObject;

public class TerminatedEventBody {
    /**
     * A debug adapter may set 'restart' to true (or to an arbitrary object) to request that the front end restarts the
     * session. The value is not interpreted by the client and passed unmodified as an attribute '__restart' to the
     * 'launch' and 'attach' requests.
     */
    public JsonObject restart;
}
