package org.javacs.debug.proto;

import com.google.gson.JsonObject;

/** Arguments for 'attach' request. Additional attributes are implementation specific. */
public class AttachRequestArguments {
    /**
     * Optional data from the previous, restarted session. The data is sent as the 'restart' attribute of the
     * 'terminated' event. The client should leave the data intact.
     */
    public JsonObject __restart;

    public int port;

    public String[] sourceRoots;
}
