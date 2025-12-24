package org.javacs.debug.proto;

public class ScopesResponseBody {
    /** The scopes of the stackframe. If the array has length zero, there are no scopes available. */
    public Scope[] scopes;
}
