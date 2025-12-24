package org.javacs.debug.proto;

/** An ExceptionOptions assigns configuration options to a set of exceptions. */
public class ExceptionOptions {
    /**
     * A path that selects a single or multiple exceptions in a tree. If 'path' is missing, the whole tree is selected.
     * By convention the first segment of the path is a category that is used to group exceptions in the UI.
     */
    public ExceptionPathSegment[] path;
    /**
     * Condition when a thrown exception should result in a break. never: never breaks, always: always breaks,
     * unhandled: breaks when excpetion unhandled, userUnhandled: breaks if the exception is not handled by user code.
     */
    public String breakMode;
}
