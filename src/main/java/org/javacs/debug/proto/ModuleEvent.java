package org.javacs.debug.proto;

/** Event message for 'module' event type. The event indicates that some information about a module has changed. */
public class ModuleEvent extends Event {
    // event: 'module';
    public ModuleEventBody body;
}
