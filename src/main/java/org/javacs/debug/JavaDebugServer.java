package org.javacs.debug;

import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.*;
import org.javacs.LogFormat;
import org.javacs.debug.proto.*;

/**
 * Debug Adapter Protocol (DAP) server implementation backed by the Java Debug Interface (JDI).
 *
 * <p>This class connects to a running JVM via a socket (dt_socket transport), exchanges DAP
 * messages with a client (e.g. Neovim via nvim-dap), and translates them into JDI operations.
 *
 * <p>Supported features include:
 * <ul>
 *   <li>Breakpoints (pending until class load, then resolved lazily)</li>
 *   <li>Stepping: over, into, out</li>
 *   <li>Thread and stack-trace enumeration</li>
 *   <li>Local variable and argument inspection</li>
 *   <li>Groovy-based expression evaluation in the context of a stopped frame</li>
 * </ul>
 */
public class JavaDebugServer implements DebugServer {
    public static void main(String[] args) { // TODO don't show references for main method
        // createLogFile();
        LOG.info(String.join(" ", args));
        new DebugAdapter(JavaDebugServer::new, System.in, System.out).run();
        System.exit(0);
    }

    private static void createLogFile() {
        try {
            // TODO make location configurable
            var logFile =
                    new FileHandler("/Users/georgefraser/Documents/jls/java-debug-server.log", false);
            logFile.setFormatter(new LogFormat());
            Logger.getLogger("").addHandler(logFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** DAP client used to send events such as {@code stopped}, {@code exited}, or {@code output}. */
    private final DebugClient client;

    /** Local source roots used to map JDI locations back to absolute file paths. */
    private List<Path> sourceRoots = List.of();

    /** The attached JVM, available after a successful {@link #attach} call. */
    private VirtualMachine vm;

    /**
     * Breakpoints requested before their target class has been loaded by the VM.
     * They are resolved lazily when a matching {@link ClassPrepareEvent} is received.
     */
    private final List<Breakpoint> pendingBreakpoints = new ArrayList<>();

    /** Monotonically increasing counter used to assign IDs to pending breakpoints. */
    private static int breakPointCounter = 0;

    /**
     * Background thread that continuously reads events from the JDI {@link EventQueue}
     * and dispatches them to the DAP client as protocol events.
     */
    class ReceiveVmEvents implements Runnable {
        @Override
        public void run() {
            var events = vm.eventQueue();
            while (true) {
                try {
                    var nextSet = events.remove();
                    for (var event : nextSet) {
                        process(event);
                    }
                } catch (VMDisconnectedException __) {
                    LOG.info("VM disconnected");
                    return;
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, e.getMessage(), e);
                    return;
                }
            }
        }

        /** Dispatches a single JDI event to the appropriate DAP client method. */
        private void process(com.sun.jdi.event.Event event) {
            LOG.info("Received " + event.toString() + " from VM");
            if (event instanceof ClassPrepareEvent) {
                var prepare = (ClassPrepareEvent) event;
                var type = prepare.referenceType();
                LOG.info("ClassPrepareRequest for class " + type.name() + " in source " + relativePath(type));
                enablePendingBreakpointsIn(type);
                vm.resume();
            } else if (event instanceof com.sun.jdi.event.BreakpointEvent) {
                var b = (com.sun.jdi.event.BreakpointEvent) event;
                var evt = new StoppedEventBody();
                evt.reason = "breakpoint";
                evt.threadId = b.thread().uniqueID();
                evt.allThreadsStopped = b.request().suspendPolicy() == EventRequest.SUSPEND_ALL;
                client.stopped(evt);
            } else if (event instanceof StepEvent) {
                var b = (StepEvent) event;
                var evt = new StoppedEventBody();
                evt.reason = "step";
                evt.threadId = b.thread().uniqueID();
                evt.allThreadsStopped = b.request().suspendPolicy() == EventRequest.SUSPEND_ALL;
                client.stopped(evt);
                // Disable event so we can create new step events
                event.request().disable();
            } else if (event instanceof VMDeathEvent) {
                client.exited(new ExitedEventBody());
            } else if (event instanceof VMDisconnectEvent) {
                client.terminated(new TerminatedEventBody());
            }
        }
    }

    /**
     * Constructs a new debug server for the given DAP client.
     *
     * <p>Installs a custom {@link Handler} that forwards {@code java.util.logging} records from the
     * {@code "debug"} logger to the client as DAP {@link OutputEventBody} messages. This allows
     * internal logging to appear in the client's debug console.
     *
     * @param client the DAP client to notify with events
     */
    public JavaDebugServer(DebugClient client) {
        this.client = client;
        class LogToConsole extends Handler {
            private final LogFormat format = new LogFormat();

            @Override
            public void publish(LogRecord r) {
                var evt = new OutputEventBody();
                evt.category = "console";
                evt.output = format.format(r);
                client.output(evt);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        }
        Logger.getLogger("debug").addHandler(new LogToConsole());
    }

    @Override
    public Capabilities initialize(InitializeRequestArguments req) {
        var resp = new Capabilities();
        resp.supportsConfigurationDoneRequest = true;
        return resp;
    }

    /**
     * Sets or clears breakpoints for a single source file.
     *
     * <p>All existing breakpoints in the requested source are disabled first, then each breakpoint
     * in the request is enabled. If the target class is not yet loaded, the breakpoint becomes
     * {@link #pendingBreakpoints pending} and will be resolved when the class is prepared.
     *
     * @param req DAP breakpoint request containing the source file and line numbers
     * @return the status of each requested breakpoint (verified or pending)
     */
    @Override
    public SetBreakpointsResponseBody setBreakpoints(SetBreakpointsArguments req) {
        LOG.info("Received " + req.breakpoints.length + " breakpoints in " + req.source.path);
        disableBreakpoints(req.source);
        // Add these breakpoints to the pending set
        var resp = new SetBreakpointsResponseBody();
        resp.breakpoints = new Breakpoint[req.breakpoints.length];
        for (var i = 0; i < req.breakpoints.length; i++) {
            resp.breakpoints[i] = enableBreakpoint(req.source, req.breakpoints[i]);
        }
        return resp;
    }

    /** Disables every existing JDI {@link BreakpointRequest} that belongs to the given source file. */
    private void disableBreakpoints(Source source) {
        for (var b : vm.eventRequestManager().breakpointRequests()) {
            if (matchesFile(b, source)) {
                LOG.info(String.format("Disable breakpoint %s:%d", source.path, b.location().lineNumber()));
                b.disable();
            }
        }
    }

    /**
     * Attempts to activate a single source breakpoint.
     *
     * <p>The implementation searches three places in order:
     * <ol>
     *   <li>Disabled breakpoints in the same file and line — re-enable them.</li>
     *   <li>Already-loaded classes that match the source file — set immediately via JDI.</li>
     *   <li>If none of the above, store as a {@link #pendingBreakpoints pending breakpoint}.</li>
     * </ol>
     *
     * @param source   the source file descriptor
     * @param b        the breakpoint specification (line, optional column, condition)
     * @return a DAP {@link Breakpoint} describing its current status
     */
    private Breakpoint enableBreakpoint(Source source, SourceBreakpoint b) {
        // Check for breakpoint in disabled breakpoints
        for (var req : vm.eventRequestManager().breakpointRequests()) {
            if (matchesFile(req, source) && matchesLine(req, b.line)) {
                return enableDisabledBreakpoint(source, req);
            }
        }
        // Check for breakpoint in loaded classes
        for (var type : loadedTypesMatching(source.path)) {
            return enableBreakpointImmediately(source, b, type);
        }
        // If class hasn't been loaded, add breakpoint to pending list
        return enableBreakpointLater(source, b);
    }

    /**
     * Returns {@code true} if the given JDI breakpoint request was set in the same source file.
     *
     * <p>This method can fail with {@link AbsentInformationException} when the class was compiled
     * without debug information (e.g. {@code -g:none}). In that case we conservatively return
     * {@code false}.
     */
    private boolean matchesFile(BreakpointRequest b, Source source) {
        try {
            var relativePath = b.location().sourcePath(vm.getDefaultStratum());
            return source.path.endsWith(relativePath);
        } catch (AbsentInformationException __) {
            LOG.warning("No source information for " + b.location());
            return false;
        }
    }

    /** Returns {@code true} if the JDI breakpoint request is located on the given line number. */
    private boolean matchesLine(BreakpointRequest b, int line) {
        return line == b.location().lineNumber(vm.getDefaultStratum());
    }

    /** Returns all JDI {@link ReferenceType}s whose source path matches the given absolute file path. */
    private List<ReferenceType> loadedTypesMatching(String absolutePath) {
        var matches = new ArrayList<ReferenceType>();
        for (var type : vm.allClasses()) {
            var path = relativePath(type);
            if (!path.isEmpty() && absolutePath.endsWith(path)) {
                matches.add(type);
            }
        }
        return matches;
    }

    /**
     * Re-enables a previously disabled breakpoint request and returns its updated DAP descriptor.
     *
     * @param source the source file that owns the breakpoint
     * @param b      the disabled JDI breakpoint request
     * @return a verified DAP {@link Breakpoint}
     */
    private Breakpoint enableDisabledBreakpoint(Source source, BreakpointRequest b) {
        LOG.info(String.format("Enable disabled breakpoint %s:%d", source.path, b.location().lineNumber()));
        b.enable();
        var ok = new Breakpoint();
        ok.verified = true;
        ok.source = source;
        ok.line = b.location().lineNumber(vm.getDefaultStratum());
        return ok;
    }

    /**
     * Creates a JDI breakpoint in an already-loaded class.
     *
     * @param source the source file
     * @param b      the requested line breakpoint
     * @param type   the JDI reference type that corresponds to the source file
     * @return a verified breakpoint, or an unverified one if the line has no executable code
     */
    private Breakpoint enableBreakpointImmediately(Source source, SourceBreakpoint b, ReferenceType type) {
        if (!tryEnableBreakpointImmediately(source, b, type)) {
            var failed = new Breakpoint();
            failed.verified = false;
            failed.message = source.name + ":" + b.line + " could not be found or had no code on it";
            return failed;
        }
        var ok = new Breakpoint();
        ok.verified = true;
        ok.source = source;
        ok.line = b.line;
        return ok;
    }

    /**
     * Attempts to create a JDI {@link BreakpointRequest} for every executable location mapped
     * to the given line in the reference type.
     *
     * @param source the source file
     * @param b      the requested line breakpoint
     * @param type   the JDI reference type
     * @return {@code true} if at least one JDI breakpoint request was created
     */
    private boolean tryEnableBreakpointImmediately(Source source, SourceBreakpoint b, ReferenceType type) {
        List<Location> locations;
        try {
            locations = type.locationsOfLine(b.line);
        } catch (AbsentInformationException __) {
            LOG.info(String.format("No locations in %s for breakpoint %s:%d", type.name(), source.path, b.line));
            return false;
        }
        for (var l : locations) {
            LOG.info(String.format("Create breakpoint %s:%d", source.path, l.lineNumber()));
            var req = vm.eventRequestManager().createBreakpointRequest(l);
            req.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            req.enable();
        }
        return true;
    }

    /**
     * Stores a breakpoint in {@link #pendingBreakpoints} because its class has not yet been loaded.
     * A {@link ClassPrepareRequest} is already (or will be) registered so that the breakpoint is
     * resolved automatically once the class becomes available.
     *
     * @param source the source file
     * @param b      the requested line breakpoint
     * @return a DAP {@link Breakpoint} marked as unverified with an explanatory message
     */
    private Breakpoint enableBreakpointLater(Source source, SourceBreakpoint b) {
        LOG.info(String.format("Enable %s:%d later", source.path, b.line));
        var pending = new Breakpoint();
        pending.id = breakPointCounter++;
        pending.source = new Source();
        pending.source.path = source.path;
        pending.line = b.line;
        pending.column = b.column;
        pending.verified = false;
        pending.message = source.name + " is not yet loaded";
        pendingBreakpoints.add(pending);
        return pending;
    }

    @Override
    public SetFunctionBreakpointsResponseBody setFunctionBreakpoints(SetFunctionBreakpointsArguments req) {
        LOG.warning("Not yet implemented");
        return new SetFunctionBreakpointsResponseBody();
    }

    @Override
    public void setExceptionBreakpoints(SetExceptionBreakpointsArguments req) {
        LOG.warning("Not yet implemented");
    }

    /**
     * Finalises the configuration phase of the DAP session.
     *
     * <p>At this point the client has finished sending breakpoints and other setup requests.
     * We register listeners for {@link ClassPrepareEvent}s so that pending breakpoints can be
     * resolved lazily, attempt to resolve any breakpoints in classes that are already loaded,
     * and finally resume the VM so execution can proceed.
     */
    @Override
    public void configurationDone() {
        listenForClassPrepareEvents();
        enablePendingBreakpointsInLoadedClasses();
        vm.resume();
    }

    /** Registers a {@link ClassPrepareRequest} for every distinct source file that has pending breakpoints. */
    private void listenForClassPrepareEvents() {
        Objects.requireNonNull(vm, "vm has not been initialized");
        // Get all file names
        var distinctSourceNames = new HashSet<String>();
        for (var b : pendingBreakpoints) {
            var path = Paths.get(b.source.path);
            var name = path.getFileName();
            distinctSourceNames.add(name.toString());
        }
        // Listen for classes with those names
        for (var name : distinctSourceNames) {
            LOG.info("Listen for ClassPrepareRequest in " + name);
            var requestClassEvent = vm.eventRequestManager().createClassPrepareRequest();
            requestClassEvent.addSourceNameFilter("*" + name);
            requestClassEvent.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            requestClassEvent.enable();
        }
    }

    @Override
    public void launch(LaunchRequestArguments req) {
        throw new UnsupportedOperationException();
    }

    /** Looks up an {@link AttachingConnector} that supports the requested transport (e.g. {@code "dt_socket"}). */
    private static AttachingConnector connector(String transport) {
        var found = new ArrayList<String>();
        for (var conn : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if (conn.transport().name().equals(transport)) {
                return conn;
            }
            found.add(conn.transport().name());
        }
        throw new RuntimeException("Couldn't find connector for transport " + transport + " in " + found);
    }

    /**
     * Attaches this debug server to a JVM listening on the given port.
     *
     * <p>The method performs three steps:
     * <ol>
     *   <li>Validates and stores the source roots.</li>
     *   <li>Connects to the target VM via the {@code dt_socket} transport, retrying for up to
     *       15 seconds in case the debuggee has not opened its port yet.</li>
     *   <li>Starts a daemon thread ({@link ReceiveVmEvents}) to drain JDI events and notify the client.
     *       Then sends an {@link InitializedEvent} so the client can begin sending breakpoints.</li>
     * </ol>
     *
     * @param req attach arguments containing the port and source roots
     */
    @Override
    public void attach(AttachRequestArguments req) {
        // Remember available source roots
        sourceRoots = new ArrayList<Path>();
        for (var string : req.sourceRoots) {
            var path = Paths.get(string);
            if (!Files.exists(path)) {
                LOG.warning(string + " does not exist");
                continue;
            } else if (!Files.isDirectory(path)) {
                LOG.warning(string + " is not a directory");
                continue;
            } else {
                LOG.info(path + " is a source root");
                sourceRoots.add(path);
            }
        }
        // Attach to the running VM
        if (!tryToConnect(req.port)) {
            throw new RuntimeException("Failed to connect after 15 attempts");
        }
        // Create a thread that reads events from the VM
        var reader = new java.lang.Thread(new ReceiveVmEvents(), "receive-vm");
        reader.setDaemon(true);
        reader.start();
        // Tell the client we are ready to receive breakpoints
        client.initialized();
    }

    /**
     * Retries the JDI socket connection with exponential-like back-off.
     *
     * <p>Waits 500 ms between attempts, giving up after roughly 15 seconds (30 attempts).
     * This grace period is necessary because the debuggee may start its JDWP listener slightly
     * after the DAP client begins the attach flow.
     *
     * @param port the JDWP port exposed by the target VM
     * @return {@code true} if the connection succeeded
     */
    private boolean tryToConnect(int port) {
        var conn = connector("dt_socket");
        var args = conn.defaultArguments();
        var intervalMs = 500;
        var tryForS = 15;
        var attempts = tryForS * 1000 / intervalMs;
        args.get("port").setValue(Integer.toString(port));
        for (var attempt = 0; attempt < attempts; attempt++) {
            try {
                vm = conn.attach(args);
                return true;
            } catch (ConnectException e) {
                LOG.warning(e.getMessage());
                try {
                    java.lang.Thread.sleep(intervalMs);
                } catch (InterruptedException __) {
                    // Nothing to do
                }
            } catch (IOException | IllegalConnectorArgumentsException e) {
                throw new RuntimeException(e);
            }
        }
        return false;
    }

    /** Attempts to resolve every pending breakpoint against already-loaded classes. */
    private void enablePendingBreakpointsInLoadedClasses() {
        Objects.requireNonNull(vm, "vm has not been initialized");
        for (var type : vm.allClasses()) {
            enablePendingBreakpointsIn(type);
        }
    }

    /**
     * Checks whether the given reference type matches any pending breakpoint source path.
     * If so, the breakpoints are converted to real JDI requests and removed from the pending list.
     *
     * @param type the JDI reference type that was just prepared or is already loaded
     */
    private void enablePendingBreakpointsIn(ReferenceType type) {
        // Check that class has source information
        var path = relativePath(type);
        if (path.isEmpty()) return;
        // Look for pending breakpoints that can be enabled
        var enabled = new ArrayList<Breakpoint>();
        for (var b : pendingBreakpoints) {
            if (b.source.path.endsWith(path)) {
                enablePendingBreakpoint(b, type);
                enabled.add(b);
            }
        }
        pendingBreakpoints.removeAll(enabled);
    }

    /**
     * Creates JDI breakpoint requests for a single pending breakpoint and notifies the client
     * of the result via a {@link BreakpointEventBody}.
     *
     * @param b    the pending DAP breakpoint
     * @param type the JDI reference type that now contains the target source
     */
    private void enablePendingBreakpoint(Breakpoint b, ReferenceType type) {
        try {
            var locations = type.locationsOfLine(b.line);
            for (var line : locations) {
                var req = vm.eventRequestManager().createBreakpointRequest(line);
                req.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                req.enable();
            }
            if (locations.isEmpty()) {
                LOG.info("No locations at " + b.source.path + ":" + b.line);
                var failed = new BreakpointEventBody();
                failed.reason = "changed";
                failed.breakpoint = b;
                b.verified = false;
                b.message = b.source.name + ":" + b.line + " could not be found or had no code on it";
                client.breakpoint(failed);
                return;
            }
            LOG.info("Enable breakpoint at " + b.source.path + ":" + b.line);
            var ok = new BreakpointEventBody();
            ok.reason = "changed";
            ok.breakpoint = b;
            b.verified = true;
            b.message = null;
            client.breakpoint(ok);
        } catch (AbsentInformationException __) {
            LOG.info("Absent information at " + b.source.path + ":" + b.line);
            var failed = new BreakpointEventBody();
            failed.reason = "changed";
            failed.breakpoint = b;
            b.verified = false;
            b.message = b.source.name + ":" + b.line + " could not be found or had no code on it";
            client.breakpoint(failed);
        }
    }

    /** Returns the relative source path of a type according to the VM's default stratum, or "" if unavailable. */
    private String relativePath(ReferenceType type) {
        try {
            for (var path : type.sourcePaths(vm.getDefaultStratum())) {
                return path;
            }
            return "";
        } catch (AbsentInformationException __) {
            return "";
        }
    }

    @Override
    public void disconnect(DisconnectArguments req) {
        try {
            vm.dispose();
        } catch (VMDisconnectedException __) {
            LOG.warning("VM has already terminated");
        }
        vm = null;
    }

    @Override
    public void terminate(TerminateArguments req) {
        vm.exit(1);
    }

    @Override
    public void continue_(ContinueArguments req) {
        vm.resume();
    }

    @Override
    public void next(NextArguments req) {
        var thread = findThread(req.threadId);
        if (thread == null) {
            LOG.warning("No thread with id " + req.threadId);
            return;
        }
        LOG.info("Send StepRequest(STEP_LINE, STEP_OVER) to VM and resume");
        var step = vm.eventRequestManager().createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
        step.addCountFilter(1);
        step.enable();
        vm.resume();
    }

    @Override
    public void stepIn(StepInArguments req) {
        var thread = findThread(req.threadId);
        if (thread == null) {
            LOG.warning("No thread with id " + req.threadId);
            return;
        }
        LOG.info("Send StepRequest(STEP_LINE, STEP_INTO) to VM and resume");
        var step = vm.eventRequestManager().createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_INTO);
        step.addCountFilter(1);
        step.enable();
        vm.resume();
    }

    @Override
    public void stepOut(StepOutArguments req) {
        var thread = findThread(req.threadId);
        if (thread == null) {
            LOG.warning("No thread with id " + req.threadId);
            return;
        }
        LOG.info("Send StepRequest(STEP_LINE, STEP_OUT) to VM and resume");
        var step = vm.eventRequestManager().createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OUT);
        step.addCountFilter(1);
        step.enable();
        vm.resume();
    }

    @Override
    public ThreadsResponseBody threads() {
        var threads = new ThreadsResponseBody();
        threads.threads = asThreads(vm.allThreads());
        return threads;
    }

    /** Converts a JDI thread list to DAP {@link org.javacs.debug.proto.Thread} array. */
    private org.javacs.debug.proto.Thread[] asThreads(List<ThreadReference> ts) {
        var result = new org.javacs.debug.proto.Thread[ts.size()];
        for (var i = 0; i < ts.size(); i++) {
            result[i] = asThread(ts.get(i));
        }
        return result;
    }

    /** Converts a single JDI {@link ThreadReference} into a DAP thread descriptor. */
    private org.javacs.debug.proto.Thread asThread(ThreadReference t) {
        var thread = new org.javacs.debug.proto.Thread();
        thread.id = t.uniqueID();
        thread.name = t.name();
        return thread;
    }

    /** Finds a JDI thread by its unique ID, or {@code null} if not found. */
    private ThreadReference findThread(long threadId) {
        for (var thread : vm.allThreads()) {
            if (thread.uniqueID() == threadId) {
                return thread;
            }
        }
        return null;
    }

    /**
     * Returns a stack trace slice for the requested thread.
     *
     * @param req contains the thread ID, start frame, and maximum number of levels
     * @return the requested stack frames and total frame count
     */
    @Override
    public StackTraceResponseBody stackTrace(StackTraceArguments req) {
        try {
            for (var t : vm.allThreads()) {
                if (t.uniqueID() == req.threadId) {
                    var length = t.frameCount() - req.startFrame;
                    if (req.levels != null && req.levels < length) {
                        length = req.levels;
                    }
                    var resp = new StackTraceResponseBody();
                    resp.stackFrames = asStackFrames(t.frames(req.startFrame, length));
                    resp.totalFrames = t.frameCount();
                    return resp;
                }
            }
            throw new RuntimeException("Couldn't find thread " + req.threadId);
        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException(e);
        }
    }

    /** Converts a list of JDI stack frames into DAP {@link StackFrame} objects. */
    private org.javacs.debug.proto.StackFrame[] asStackFrames(List<com.sun.jdi.StackFrame> fs) {
        var result = new org.javacs.debug.proto.StackFrame[fs.size()];
        for (var i = 0; i < fs.size(); i++) {
            result[i] = asStackFrame(fs.get(i));
        }
        return result;
    }

    /** Converts a single JDI {@link com.sun.jdi.StackFrame} into a DAP stack frame. */
    private org.javacs.debug.proto.StackFrame asStackFrame(com.sun.jdi.StackFrame f) {
        var frame = new org.javacs.debug.proto.StackFrame();
        frame.id = uniqueFrameId(f);
        frame.name = f.location().method().name();
        frame.source = asSource(f.location());
        frame.line = f.location().lineNumber();
        return frame;
    }

    /** Builds a DAP {@link Source} from a JDI location, attempting to resolve an absolute file path. */
    private Source asSource(Location l) {
        try {
            var path = findSource(l);
            var src = new Source();
            src.name = l.sourceName();
            src.path = Objects.toString(path, null);
            return src;
        } catch (AbsentInformationException __) {
            var src = new Source();
            src.path = relativePath(l.declaringType());
            src.name = l.declaringType().name();
            src.presentationHint = "deemphasize";
            return src;
        }
    }

    /** Tracks source paths we have already warned about to avoid spamming the log. */
    private static final Set<String> warnedCouldNotFind = new HashSet<>();

    /**
     * Resolves an absolute source file path from a JDI location by searching {@link #sourceRoots}.
     *
     * @param l a JDI location that exposes a relative source path
     * @return the absolute path, or {@code null} if none of the roots contain the file
     * @throws AbsentInformationException if the location has no source information
     */
    private Path findSource(Location l) throws AbsentInformationException {
        var relative = l.sourcePath();
        for (var root : sourceRoots) {
            var absolute = root.resolve(relative);
            if (Files.exists(absolute)) {
                return absolute;
            }
        }
        if (!warnedCouldNotFind.contains(relative)) {
            LOG.warning("Could not find " + relative);
            warnedCouldNotFind.add(relative);
        }
        return null;
    }

    /**
     * The DAP specification reserves low frame IDs (e.g. 0) for special purposes, so we offset
     * every generated frame ID by this constant to avoid collisions.
     */
    private static final int FRAME_OFFSET = 100;

    /**
     * Generates a stable, unique identifier for a JDI stack frame that can be used in DAP
     * {@link StackFrame} and {@link ScopesArguments} messages.
     *
     * <p>The ID is computed by enumerating every thread and every frame in order, starting from
     * {@link #FRAME_OFFSET}. This makes the mapping reversible via {@link #findFrame}.
     *
     * @param f the JDI stack frame
     * @return a positive integer uniquely identifying the frame
     */
    private long uniqueFrameId(com.sun.jdi.StackFrame f) {
        try {
            long count = FRAME_OFFSET;
            for (var thread : f.virtualMachine().allThreads()) {
                if (thread.equals(f.thread())) {
                    for (var frame : thread.frames()) {
                        if (frame.equals(f)) {
                            return count;
                        } else {
                            count++;
                        }
                    }
                } else {
                    count += thread.frameCount();
                }
            }
            return count;
        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Reverses {@link #uniqueFrameId} to locate the JDI stack frame corresponding to a DAP frame ID.
     *
     * @param id the frame identifier received from the client
     * @return the matching JDI stack frame
     * @throws RuntimeException if the frame cannot be found
     */
    private com.sun.jdi.StackFrame findFrame(long id) {
        try {
            long count = FRAME_OFFSET;
            for (var thread : vm.allThreads()) {
                if (id < count + thread.frameCount()) {
                    var offset = (int) (id - count);
                    return thread.frame(offset);
                } else {
                    count += thread.frameCount();
                }
            }
            throw new RuntimeException("Couldn't find frame " + id);
        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the variable scopes visible in a given stack frame.
     *
     * <p>Two scopes are produced for each frame:
     * <ul>
     *   <li>{@code Locals} — variables whose {@code isArgument()} is {@code false}</li>
     *   <li>{@code Arguments} — variables whose {@code isArgument()} is {@code true}</li>
     * </ul>
     *
     * <p>The scopes are encoded into the {@code variablesReference} field by the formula
     * {@code frameId * 2} (locals) and {@code frameId * 2 + 1} (arguments), which
     * {@link #variables} later decodes.
     *
     * @param req contains the frame ID
     * @return the locals and arguments scopes for that frame
     */
    @Override
    public ScopesResponseBody scopes(ScopesArguments req) {
        var resp = new ScopesResponseBody();
        var locals = new Scope();
        locals.name = "Locals";
        locals.presentationHint = "locals";
        locals.variablesReference = req.frameId * 2;
        var arguments = new Scope();
        arguments.name = "Arguments";
        arguments.presentationHint = "arguments";
        arguments.variablesReference = req.frameId * 2 + 1;
        resp.scopes = new Scope[] {locals, arguments};
        return resp;
    }

    /**
     * Returns the variables belonging to a scope.
     *
     * <p>The {@code variablesReference} is decoded as follows:
     * <ul>
     *   <li>frame ID = {@code variablesReference / 2}</li>
     *   <li>scope kind = {@code variablesReference % 2} ({@code 0} = locals, {@code 1} = arguments)</li>
     * </ul>
     *
     * @param req contains the variables reference returned by {@link #scopes}
     * @return the list of variables with name, type, and stringified value
     */
    @Override
    public VariablesResponseBody variables(VariablesArguments req) {
        var frameId = req.variablesReference / 2;
        var scopeId = (int) (req.variablesReference % 2);
        var argumentScope = scopeId == 1;
        var frame = findFrame(frameId);
        List<LocalVariable> visible;
        try {
            visible = frame.visibleVariables();
        } catch (AbsentInformationException __) {
            LOG.warning(String.format("No visible variable information in %s", frame.location()));
            return new VariablesResponseBody();
        }
        var values = frame.getValues(visible);
        var thread = frame.thread();
        var variables = new ArrayList<Variable>();
        for (var v : visible) {
            if (v.isArgument() != argumentScope) continue;
            var w = new Variable();
            w.name = v.name();
            w.value = print(values.get(v), thread);
            w.type = v.typeName();
            // TODO set variablesReference and allow inspecting structure of collections and POJOs
            // TODO set variablePresentationHint
            variables.add(w);
        }
        var resp = new VariablesResponseBody();
        resp.variables = variables.toArray(Variable[]::new);
        return resp;
    }

    /**
     * Formats a JDI {@link Value} as a human-readable string.
     *
     * <p>Primitive values are rendered with {@link Value#toString()}. Object references are
     * delegated to {@link #printObject} which attempts to call the object's {@code toString()} method
     * inside the debuggee VM so that overridden implementations are respected.
     *
     * @param value  the JDI value
     * @param thread the thread in which to invoke methods (for object references)
     * @return a display string suitable for the DAP client
     */
    private String print(Value value, ThreadReference t) {
        if (value == null) {
            return "null";
        } else if (value instanceof ObjectReference) {
            return printObject((ObjectReference) value, t);
        } else {
            return value.toString();
        }
    }

    /**
     * Invokes {@code toString()} on an object reference within the debuggee VM.
     *
     * <p>If the invocation throws an exception, the exception type name is returned instead of the
     * string value. If no {@code toString()} method is found, falls back to the reference's
     * default string representation.
     *
     * @param object the object whose string representation is desired
     * @param t      the suspended thread on which to perform the invocation
     * @return the result of {@code toString()}, or an error description
     */
    private String printObject(ObjectReference object, ThreadReference t) {
        var type = object.referenceType();
        for (var method : type.methodsByName("toString", "()Ljava/lang/String;")) {
            try {
                var string = (StringReference) object.invokeMethod(t, method, List.of(), 0);
                return string.value();
            } catch (InvocationException e) {
                return String.format("toString() threw %s", e.exception().type().name());
            } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException e) {
                throw new RuntimeException(e);
            }
        }
        return object.toString();
    }

    /**
     * Evaluates an expression as a Groovy script within the context of a stopped stack frame.
     *
     * <p>The script can reference class fields, local variables, and the special variable
     * {@code frame} which is bound to the JDI {@link com.sun.jdi.StackFrame} object. This is useful
     * for ad-hoc inspection or arithmetic when debugging.
     *
     * @param req contains the expression text and the frame ID to evaluate in
     * @return the string result of the evaluation, or an error message if the script fails
     */
    @Override
    public EvaluateResponseBody evaluate(EvaluateArguments req) {
        EvaluateResponseBody response = new EvaluateResponseBody();
        try {
            com.sun.jdi.StackFrame frame = findFrame(req.frameId);
            Binding binding = new Binding();
            // Make the JDI StackFrame available under the name "frame"
            binding.setProperty("frame", frame);
            // Inject class fields into the binding
            try {
                for (Field field : frame.thisObject().referenceType().allFields()) {
                    if (field.isStatic()) {
                        binding.setVariable(field.name(), frame.thisObject().referenceType().getValue(field));
                    } else {
                        binding.setProperty(field.name(), frame.thisObject().getValue(field));
                    }
                }
            } catch (ClassNotPreparedException e) {
                // ignore
            }
            // Inject visible local variables into the binding
            try {
                for (LocalVariable variable : frame.visibleVariables()) {
                    binding.setVariable(variable.name(), frame.getValue(variable));
                }
            } catch (AbsentInformationException e) {
                // ignore
            }
            GroovyShell shell = new GroovyShell(binding);
            Object result = shell.evaluate(req.expression);
            response.result = result.toString();
        } catch (Exception e) {
            response.result = "Evaluation error " + e.getMessage();
        }
        return response;
    }

    private static final Logger LOG = Logger.getLogger("debug");
}
