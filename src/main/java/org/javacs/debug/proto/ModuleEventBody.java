package org.javacs.debug.proto;

public class ModuleEventBody {
    /** The reason for the event. 'new' | 'changed' | 'removed'. */
    public String reason;
    /** The new, changed, or removed module. In case of 'removed' only the module id is used. */
    public Module module;
}
