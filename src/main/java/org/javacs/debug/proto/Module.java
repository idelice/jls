package org.javacs.debug.proto;

/**
 * A Module object represents a row in the modules view. Two attributes are mandatory: an id identifies a module in the
 * modules view and is used in a ModuleEvent for identifying a module for adding, updating or deleting. The name is used
 * to minimally render the module in the UI.
 *
 * <p>Additional attributes can be added to the module. They will show up in the module View if they have a
 * corresponding ColumnDescriptor.
 *
 * <p>To avoid an unnecessary proliferation of additional attributes with similar semantics but different names we
 * recommend to re-use attributes from the 'recommended' list below first, and only introduce new attributes if nothing
 * appropriate could be found.
 */
public class Module {
    /** Unique identifier for the module. */
    public String id;
    /** A name of the module. */
    public String name;
    /**
     * optional but recommended attributes. always try to use these first before introducing additional attributes.
     *
     * <p>Logical full path to the module. The exact definition is implementation defined, but usually this would be a
     * full path to the on-disk file for the module.
     */
    public String path;
    /** True if the module is optimized. */
    public Boolean isOptimized;
    /** True if the module is considered 'user code' by a debugger that supports 'Just My Code'. */
    public Boolean isUserCode;
    /** Version of Module. */
    public String version;
    /**
     * User understandable description of if symbols were found for the module (ex: 'Symbols Loaded', 'Symbols not
     * found', etc.
     */
    public String symbolStatus;
    /** Logical full path to the symbol file. The exact definition is implementation defined. */
    public String symbolFilePath;
    /** Module created or modified. */
    public String dateTimeStamp;
    /** Address range covered by this module. */
    public String addressRange;
}
