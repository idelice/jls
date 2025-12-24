package org.javacs.debug.proto;

/**
 * GotoTargets request; value of command field is 'gotoTargets'. This request retrieves the possible goto targets for
 * the specified source location. These targets can be used in the 'goto' request. The GotoTargets request may only be
 * called if the 'supportsGotoTargetsRequest' capability exists and is true.
 */
public class GotoTargetsRequest extends Request {
    public GotoTargetsArguments arguments;
}
