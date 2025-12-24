package org.javacs.debug.proto;

/**
 * Modules request; value of command field is 'modules'. Modules can be retrieved from the debug adapter with the
 * ModulesRequest which can either return all modules or a range of modules to support paging.
 */
public class ModulesRequest extends Request {
    public ModulesArguments arguments;
}
