package org.javacs.debug.proto;

import com.google.gson.JsonObject;

/** Arguments for 'launch' request. Additional attributes are implementation specific. */
public class LaunchRequestArguments {
    /** If noDebug is true the launch request should launch the program without enabling debugging. */
    public Boolean noDebug;
    /**
     * Optional data from the previous, restarted session. The data is sent as the 'restart' attribute of the
     * 'terminated' event. The client should leave the data intact.
     */
    public JsonObject __restart;
}
