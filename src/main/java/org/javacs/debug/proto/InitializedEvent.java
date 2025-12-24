package org.javacs.debug.proto;

/**
 * Event message for 'initialized' event type. This event indicates that the debug adapter is ready to accept
 * configuration requests (e.g. SetBreakpointsRequest, SetExceptionBreakpointsRequest). A debug adapter is expected to
 * send this event when it is ready to accept configuration requests (but not before the 'initialize' request has
 * finished). The sequence of events/requests is as follows: - adapters sends 'initialized' event (after the
 * 'initialize' request has returned) - frontend sends zero or more 'setBreakpoints' requests - frontend sends one
 * 'setFunctionBreakpoints' request - frontend sends a 'setExceptionBreakpoints' request if one or more
 * 'exceptionBreakpointFilters' have been defined (or if 'supportsConfigurationDoneRequest' is not defined or false) -
 * frontend sends other future configuration requests - frontend sends one 'configurationDone' request to indicate the
 * end of the configuration.
 */
public class InitializedEvent extends Event {
    // event: 'initialized';
}
