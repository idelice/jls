// Forked from JavacTaskImpl
/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package org.javacs;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.*;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.comp.Annotate;
import com.sun.tools.javac.comp.Check;
import com.sun.tools.javac.comp.CompileStates;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.comp.Modules;
import com.sun.tools.javac.main.Arguments;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.DefinedBy;
import com.sun.tools.javac.util.DefinedBy.Api;
import com.sun.tools.javac.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

/**
 * A pool of reusable JavacTasks. When a task is no valid anymore, it is returned to the pool, and its Context may be
 * reused for future processing in some cases. The reuse is achieved by replacing some components (most notably
 * JavaCompiler and Log) with reusable counterparts, and by cleaning up leftovers from previous compilation.
 *
 * <p>For each combination of options, a separate task/context is created and kept, as most option values are cached
 * inside components themselves.
 *
 * <p>When the compilation redefines sensitive classes (e.g. classes in the the java.* packages), the task/context is
 * not reused.
 *
 * <p>When the task is reused, then packages that were already listed won't be listed again.
 *
 * <p>Care must be taken to only return tasks that won't be used by the original caller.
 *
 * <p>Care must also be taken when custom components are installed, as those are not cleaned when the task/context is
 * reused, and subsequent getTask may return a task based on a context with these custom components.
 *
 * <p><b>This is NOT part of any supported API. If you write code that depends on this, you do so at your own risk. This
 * code and its internal interfaces are subject to change or deletion without notice.</b>
 */
class ReusableCompiler {

    private static final Logger LOG = Logger.getLogger("main");
    private static final JavacTool systemProvider = JavacTool.create();

    private final Map<List<String>, ReusableContextState> contexts = new HashMap<>();

    private static class ReusableContextState {
        final ReusableContext context;
        boolean checkedOut;

        ReusableContextState(ReusableContext context) {
            this.context = context;
        }
    }

    /**
     * Creates a new task as if by {@link javax.tools.JavaCompiler#getTask} and runs the provided worker with it. The
     * task is only valid while the worker is running. The internal structures may be reused from some previous
     * compilation.
     *
     * @param fileManager a file manager; if {@code null} use the compiler's standard filemanager
     * @param diagnosticListener a diagnostic listener; if {@code null} use the compiler's default method for reporting
     *     diagnostics
     * @param options compiler options, {@code null} means no options
     * @param classes names of classes to be processed by annotation processing, {@code null} means no class names
     * @param compilationUnits the compilation units to compile, {@code null} means no compilation units
     * @return an object representing the compilation
     * @throws RuntimeException if an unrecoverable error occurred in a user supplied component. The {@linkplain
     *     Throwable#getCause() cause} will be the error in user code.
     * @throws IllegalArgumentException if any of the options are invalid, or if any of the given compilation units are
     *     of other kind than {@linkplain JavaFileObject.Kind#SOURCE source}
     */
    Borrow getTask(
            JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Iterable<String> options,
            Iterable<String> classes,
            Iterable<? extends JavaFileObject> compilationUnits) {
        List<String> opts =
                StreamSupport.stream(options.spliterator(), false).collect(Collectors.toCollection(ArrayList::new));
        var key = List.copyOf(opts);
        var state = contexts.get(key);
        if (state == null) {
            state = new ReusableContextState(new ReusableContext(opts));
            contexts.put(key, state);
        }
        if (state.checkedOut) {
            throw new RuntimeException("Compiler is already in-use!");
        }
        state.checkedOut = true;
        JavacTaskImpl task =
                (JavacTaskImpl)
                        systemProvider.getTask(
                                null, fileManager, diagnosticListener, opts, classes, compilationUnits, state.context);

        task.addTaskListener(state.context);

        return new Borrow(task, state);
    }

    class Borrow implements AutoCloseable {
        final JavacTask task;
        final ReusableContextState state;
        boolean closed;

        Borrow(JavacTask task, ReusableContextState state) {
            this.task = task;
            this.state = state;
        }

        @Override
        public void close() {
            if (closed) return;
            try {
                // Try to clean up the context and task.
                // If either fails due to a corrupted state (e.g., after AP failure),
                // we still need to unlock the compiler so subsequent attempts can proceed.
                try {
                    state.context.clear();
                } catch (Throwable e) {
                    // Context cleanup failed - likely due to AP infrastructure corruption.
                    // Log and continue. We need to unlock the compiler.
                    LOG.fine("Context cleanup failed (likely due to AP error): " + e.getMessage());
                }

                try {
                    var method = JavacTaskImpl.class.getDeclaredMethod("cleanup");
                    method.setAccessible(true);
                    method.invoke(task);
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    LOG.fine("Task cleanup failed: " + e.getMessage());
                    // Don't throw - we still need to unlock the compiler
                }
            } finally {
                // CRITICAL: Always unlock the compiler, even if cleanup operations fail.
                // If we don't do this, the compiler will be permanently locked.
                state.checkedOut = false;
                closed = true;
            }
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
            drop(javax.annotation.processing.Processor.class);
            drop(JavaFileManager.class);
            drop(JavacTask.class);
            drop(JavacTrees.class);
            drop(JavacElements.class);
            // Lombok/AP state must not leak between tasks in a reusable context.
            dropByClassName("com.sun.tools.javac.processing.JavacProcessingEnvironment");
            dropByClassName("com.sun.tools.javac.processing.JavacProcessingEnvironment$DiscoveredProcessors");

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
            }
        }

        private void dropByClassName(String className) {
            try {
                @SuppressWarnings("unchecked")
                var cls = (Class<Object>) Class.forName(className);
                drop(cls);
            } catch (ClassNotFoundException ignored) {
                // No-op: class name is JDK-internal and may vary by version.
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

        /**
         * Reusable JavaCompiler; exposes a method to clean up the component from leftovers associated with previous
         * compilations.
         */
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
                resetAnnotationProcessingState();
                newRound();
            }

            @Override
            protected void checkReusable() {
                // do nothing - it's ok to reuse the compiler
            }

            private void resetAnnotationProcessingState() {
                try {
                    // AP state from the previous task must be reset, otherwise javac can keep
                    // processAnnotations=true with a null deferredDiagnosticHandler in the next task.
                    setBooleanField("processAnnotations", false);
                    setObjectField("deferredDiagnosticHandler", null);
                    closeAndClearProcessingEnvironment();
                    setBooleanFieldIfPresent("annotationProcessingOccurred", false);
                    setBooleanFieldIfPresent("explicitAnnotationProcessingRequested", false);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    ReusableCompiler.LOG.warning(
                            "Failed to reset javac annotation-processing state; compiler reuse may be unstable: "
                                    + e.getMessage());
                }
            }

            private void closeAndClearProcessingEnvironment() throws ReflectiveOperationException {
                var field = JavaCompiler.class.getDeclaredField("procEnvImpl");
                field.setAccessible(true);
                var procEnv = field.get(this);
                if (procEnv != null) {
                    try {
                        Method close = procEnv.getClass().getMethod("close");
                        close.invoke(procEnv);
                    } catch (NoSuchMethodException ignored) {
                        // Keep going; we'll still clear the field.
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        ReusableCompiler.LOG.fine("Failed to close stale procEnvImpl: " + e.getMessage());
                    }
                }
                field.set(this, null);
            }

            private void setBooleanField(String fieldName, boolean value) throws ReflectiveOperationException {
                var field = JavaCompiler.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setBoolean(this, value);
            }

            private void setBooleanFieldIfPresent(String fieldName, boolean value) throws ReflectiveOperationException {
                try {
                    setBooleanField(fieldName, value);
                } catch (NoSuchFieldException ignored) {
                    // Field name differs across JDKs; safe to ignore.
                }
            }

            private void setObjectField(String fieldName, Object value) throws ReflectiveOperationException {
                var field = JavaCompiler.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(this, value);
            }
        }

        /**
         * Reusable Log; exposes a method to clean up the component from leftovers associated with previous
         * compilations.
         */
        static class ReusableLog extends Log {

            static final Factory<Log> factory = ReusableLog::new;

            Context context;

            ReusableLog(Context context) {
                super(context);
                this.context = context;
            }

            void clear() {
                recorded.clear();
                sourceMap.clear();
                nerrors = 0;
                nwarnings = 0;
                // Set a fake listener that will lazily lookup the context for the 'real' listener. Since
                // this field is never updated when a new task is created, we cannot simply reset the field
                // or keep old value. This is a hack to workaround the limitations in the current infrastructure.
                diagListener =
                        new DiagnosticListener<>() {
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
