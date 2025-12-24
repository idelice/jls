package org.javacs.debug.proto;

/**
 * Event message for 'loadedSource' event type. The event indicates that some source has been added, changed, or removed
 * from the set of all loaded sources.
 */
public class LoadedSourceEvent extends Event {
    // event: 'loadedSource';
    public LoadedSourceEventBody body;
}
