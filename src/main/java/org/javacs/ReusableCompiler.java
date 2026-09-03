// Forked from georgewfraser/java-language-server, which forked it from JavacTaskImpl.
/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 */
package org.javacs;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.*;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.main.Arguments;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.platform.PlatformDescription;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.DefinedBy;
import com.sun.tools.javac.util.DefinedBy.Api;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Options;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

/**
 * Reuses a single warm javac Context across compiles. Reuse works by replacing Log and
 * JavaCompiler with reusable counterparts and cleaning up leftovers ({@link ReusableContext#clear})
 * on each return. A fresh context is created whenever the options change. The context is checked
 * out for the duration of one worker call (serial, single-threaded) — same model as upstream
 * java-language-server.
 *
 * <p><b>Not part of any supported API.</b>
 */
class ReusableCompiler {
    private static final Logger LOG = Logger.getLogger("main");
    private static final JavacTool systemProvider = JavacTool.create();

    private List<String> currentOptions = new ArrayList<>();
    private ReusableContext currentContext;
    private boolean checkedOut;

    /**
     * Runs {@code worker} with a task backed by a (possibly reused) warm context, then returns an
     * {@link AutoCloseable} that the caller MUST close once it has finished consuming the task's
     * Trees/Elements. Closing clears the context and makes it available for the next compile.
     * The task and anything derived from it are only valid until that close.
     */
    <T> AutoCloseable compile(
            JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            List<String> options,
            Collection<? extends JavaFileObject> compilationUnits,
            Function<JavacTask, T> worker) {
        var started = System.nanoTime();
        Borrow borrow;
        try {
            borrow = getTask(fileManager, diagnosticListener, options, null, compilationUnits);
        } catch (RuntimeException | Error e) {
            // getTask failed (e.g. invalid options) after setting checkedOut=true.
            // Clear partial registrations from the context so it's reusable, but keep it warm.
            checkedOut = false;
            if (currentContext != null) {
                currentContext.clear();
            }
            LOG.warning("[warm-context] getTask failed, context cleared: " + e.getMessage());
            throw e;
        }
        try {
            worker.apply(borrow.task);
        } catch (RuntimeException | Error e) {
            borrow.close();
            throw e;
        }
        LOG.info("[warm-context] reused files=" + compilationUnits.size()
                + " taskMs=" + ((System.nanoTime() - started) / 1_000_000L));
        return borrow;
    }

    private Borrow getTask(
            JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Iterable<String> options,
            Iterable<String> classes,
            Iterable<? extends JavaFileObject> compilationUnits) {
        if (checkedOut) {
            throw new RuntimeException("Compiler is already in-use!");
        }
        checkedOut = true;
        var opts = new ArrayList<String>();
        options.forEach(opts::add);
        if (!opts.equals(currentOptions)) {
            currentOptions = opts;
            currentContext = new ReusableContext(opts);
        }
        var task = (JavacTaskImpl) systemProvider.getTask(
                null, fileManager, diagnosticListener, opts, classes, compilationUnits, currentContext);
        task.addTaskListener(currentContext);
        return new Borrow(task);
    }

    class Borrow implements AutoCloseable {
        final JavacTask task;
        boolean closed;

        Borrow(JavacTask task) {
            this.task = task;
        }

        @Override
        public void close() {
            if (closed) return;
            currentContext.clear();
            try {
                var method = JavacTaskImpl.class.getDeclaredMethod("cleanup");
                method.setAccessible(true);
                method.invoke(task);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
            checkedOut = false;
            closed = true;
        }
    }

    static class ReusableContext extends Context implements TaskListener {
        List<String> arguments;

        ReusableContext(List<String> arguments) {
            super();
            this.arguments = arguments;
            put(Log.logKey, ReusableLog.factory);
            put(JavaCompiler.compilerKey, ReusableJavaCompiler.factory);
        }

        void clear() {
            drop(Arguments.argsKey);
            drop(DiagnosticListener.class);
            drop(Log.outKey);
            drop(Log.errKey);
            drop(JavaFileManager.class);
            drop(JavacTask.class);
            drop(JavacTrees.class);
            drop(JavacElements.class);
            // --release installs a PlatformDescription and a DelegatingJavaFileManager; drop the
            // stale platform so it isn't reused across rounds.
            drop(PlatformDescription.class);
            if (ht.get(Log.logKey) instanceof ReusableLog) {
                // log already inited - not first round
                ((ReusableLog) Log.instance(this)).clear();
                Enter.instance(this).newRound();
                ((ReusableJavaCompiler) ReusableJavaCompiler.instance(this)).clear();
                Types.instance(this).newRound();
                Check.instance(this).newRound();
                Modules.instance(this).newRound();
                Annotate.instance(this).newRound();
                CompileStates.instance(this).clear();
                MultiTaskListener.instance(this).clear();
                // Essential for --release reuse: javac's handleReleaseOptions() writes implicit
                // -source/-target entries into the shared Options when --release is processed. If
                // Options is not cleared, the second compile sees a leftover -source and throws
                // "option --source cannot be used together with --release". Matches the JDK's own
                // JavacTaskPool.ReusableContext.clear().
                Options.instance(this).clear();
            }
        }

        @Override
        @DefinedBy(Api.COMPILER_TREE)
        public void finished(TaskEvent e) {
            // do nothing
        }

        @Override
        @DefinedBy(Api.COMPILER_TREE)
        public void started(TaskEvent e) {
            // do nothing
        }

        <T> void drop(Key<T> k) {
            ht.remove(k);
        }

        <T> void drop(Class<T> c) {
            ht.remove(key(c));
        }

        /** Reusable JavaCompiler; cleans up leftovers from previous compilations. */
        static class ReusableJavaCompiler extends JavaCompiler {
            static final Factory<JavaCompiler> factory = ReusableJavaCompiler::new;

            ReusableJavaCompiler(Context context) {
                super(context);
            }

            @Override
            public void close() {
                // do nothing
            }

            void clear() {
                newRound();
            }

            @Override
            protected void checkReusable() {
                // do nothing - it's ok to reuse the compiler
            }
        }

        /** Reusable Log; cleans up leftovers from previous compilations. */
        static class ReusableLog extends Log {
            static final Factory<Log> factory = ReusableLog::new;
            Context context;

            ReusableLog(Context context) {
                super(context);
                this.context = context;
            }

            public void clear() {
                recorded.clear();
                sourceMap.clear();
                nerrors = 0;
                nwarnings = 0;
                // Lazily look up the 'real' listener from the context on each report. This field is
                // never updated when a new task is created, so we cannot simply reset or keep it.
                diagListener = new DiagnosticListener<>() {
                    DiagnosticListener<JavaFileObject> cachedListener;

                    @Override
                    @DefinedBy(Api.COMPILER)
                    @SuppressWarnings("unchecked")
                    public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
                        if (cachedListener == null) {
                            cachedListener = context.get(DiagnosticListener.class);
                        }
                        cachedListener.report(diagnostic);
                    }
                };
            }
        }
    }
}
