package org.javacs.debug.proto;

/** Arguments for 'modules' request. */
public class ModulesArguments {
    /** The index of the first module to return; if omitted modules start at 0. */
    public Integer startModule;
    /** The number of modules to return. If moduleCount is not specified or 0, all modules are returned. */
    public Integer moduleCount;
}
