package org.javacs.debug.proto;

public class StoppedEventBody {
    /**
     * The reason for the event. For backward compatibility this string is shown in the UI if the 'description'
     * attribute is missing (but it must not be translated). Values: 'step', 'breakpoint', 'exception', 'pause',
     * 'entry', 'goto', 'function breakpoint', 'data breakpoint', etc.
     */
    public String reason;
    /**
     * The full reason for the event, e.g. 'Paused on exception'. This string is shown in the UI as is and must be
     * translated.
     */
    public String description;
    /** The thread which was stopped. */
    public Long threadId;
    /** A value of true hints to the frontend that this event should not change the focus. */
    public Boolean preserveFocusHint;
    /**
     * Additional information. E.g. if reason is 'exception', text contains the exception name. This string is shown in
     * the UI.
     */
    public String text;
    /**
     * If 'allThreadsStopped' is true, a debug adapter can announce that all threads have stopped. - The client should
     * use this information to enable that all threads can be expanded to access their stacktraces. - If the attribute
     * is missing or false, only the thread with the given threadId can be expanded.
     */
    public Boolean allThreadsStopped;
}
