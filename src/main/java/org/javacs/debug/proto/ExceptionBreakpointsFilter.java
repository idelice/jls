package org.javacs.debug.proto;

import com.google.gson.annotations.SerializedName;

/** An ExceptionBreakpointsFilter is shown in the UI as an option for configuring how exceptions are dealt with. */
public class ExceptionBreakpointsFilter {
    /** The internal ID of the filter. This value is passed to the setExceptionBreakpoints request. */
    public String filter;
    /** The name of the filter. This will be shown in the UI. */
    public String label;
    /** Initial value of the filter. If not specified a value 'false' is assumed. */
    @SerializedName("default")
    public Boolean _default;
}
