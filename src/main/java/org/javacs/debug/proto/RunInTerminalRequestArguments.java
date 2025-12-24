package org.javacs.debug.proto;

import java.util.Map;

/** Arguments for 'runInTerminal' request. */
public class RunInTerminalRequestArguments {
    /** What kind of terminal to launch. 'integrated' | 'external'. */
    public String kind;
    /** Optional title of the terminal. */
    public String title;
    /** Working directory of the command. */
    public String cwd;
    /** List of arguments. The first argument is the command to run. */
    public String[] args;
    /** Environment key-value pairs that are added to or removed from the default environment. */
    public Map<String, String> env;
}
