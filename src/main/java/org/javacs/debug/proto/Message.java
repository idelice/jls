package org.javacs.debug.proto;

import java.util.Map;

/** A structured message object. Used to return errors from requests. */
public class Message {
    /** Unique identifier for the message. */
    public int id;
    /**
     * A format string for the message. Embedded variables have the form '{name}'. If variable name starts with an
     * underscore character, the variable does not contain user data (PII) and can be safely used for telemetry
     * purposes.
     */
    public String format;
    /** An object used as a dictionary for looking up the variables in the format string. */
    Map<String, String> variables;
    /** If true send to telemetry. */
    public Boolean sendTelemetry;
    /** If true show user. */
    public Boolean showUser;
    /** An optional url where additional information about this message can be found. */
    public String url;
    /** An optional label that is presented to the user as the UI for opening the url. */
    public String urlLabel;
}
