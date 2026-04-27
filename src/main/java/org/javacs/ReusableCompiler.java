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
import com.sun.tools.javac.util.Options;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;
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
    private final SlotContext legacySlot = new SlotContext();

    static class TaskCreationException extends RuntimeException {
        TaskCreationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** One dedicated javac context owned by a single cache slot. */
    static class SlotContext {
        ReusableContext context;
        boolean inUse;

        SlotContext() {
            this.context = new ReusableContext();
        }

        void reset() {
            this.context = new ReusableContext();
            this.inUse = false;
        }
    }

    Borrow getTask(
            JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Iterable<String> options,
            Iterable<String> classes,
            Iterable<? extends JavaFileObject> compilationUnits) {
        var opts = new java.util.ArrayList<String>();
        if (options != null) {
            for (var option : options) {
                opts.add(option);
            }
        }
        var classNames = new java.util.ArrayList<String>();
        if (classes != null) {
            for (var className : classes) {
                classNames.add(className);
            }
        }
        if (legacySlot.inUse) throw new IllegalStateException("Slot already in use");
        legacySlot.inUse = true;
        try {
            var task = (JavacTaskImpl) systemProvider.getTask(
                    null, fileManager, diagnosticListener, opts, classNames, compilationUnits, legacySlot.context);
            task.addTaskListener(legacySlot.context);
            return new Borrow(this, legacySlot, task);
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError error) {
                legacySlot.inUse = false;
                throw error;
            }
            if (e instanceof ThreadDeath error) {
                legacySlot.inUse = false;
                throw error;
            }
            if (e instanceof LinkageError error) {
                legacySlot.inUse = false;
                throw error;
            }
            legacySlot.reset();
            LOG.warning(String.format(
                    "[perf] javac_get_task_failed options=%s reason=%s",
                    compactOptions(opts), e.getMessage()));
            throw new TaskCreationException("Failed to create javac task: " + e.getMessage(), e);
        }
    }

    JavacTask createTask(
            SlotContext slot,
            JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            List<String> options,
            Iterable<? extends JavaFileObject> compilationUnits) {
        if (slot.inUse) throw new IllegalStateException("Slot already in use");
        slot.inUse = true;
        try {
            var task = (JavacTaskImpl) systemProvider.getTask(
                    null, fileManager, diagnosticListener, options, List.of(), compilationUnits, slot.context);
            task.addTaskListener(slot.context);
            return task;
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError error) {
                slot.inUse = false;
                throw error;
            }
            if (e instanceof ThreadDeath error) {
                slot.inUse = false;
                throw error;
            }
            if (e instanceof LinkageError error) {
                slot.inUse = false;
                throw error;
            }
            slot.reset();
            LOG.warning(String.format(
                    "[perf] javac_get_task_failed options=%s reason=%s",
                    compactOptions(options), e.getMessage()));
            throw new TaskCreationException("Failed to create javac task: " + e.getMessage(), e);
        }
    }

    JavacTask createSingleUseTask(
            JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            List<String> options,
            Iterable<? extends JavaFileObject> compilationUnits) {
        try {
            return systemProvider.getTask(
                    null, fileManager, diagnosticListener, options, List.of(), compilationUnits);
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError error) {
                throw error;
            }
            if (e instanceof ThreadDeath error) {
                throw error;
            }
            if (e instanceof LinkageError error) {
                throw error;
            }
            LOG.warning(String.format(
                    "[perf] javac_get_task_failed options=%s reason=%s",
                    compactOptions(options), e.getMessage()));
            throw new TaskCreationException("Failed to create javac task: " + e.getMessage(), e);
        }
    }

    private static List<String> compactOptions(List<String> options) {
        var compact = new java.util.ArrayList<String>();
        for (var i = 0; i < options.size(); i++) {
            var option = options.get(i);
            switch (option) {
                case "-classpath", "--class-path", "-cp", "-processorpath" -> {
                    compact.add(option);
                    if (i + 1 < options.size()) {
                        compact.add("<paths>");
                        i++;
                    }
                }
                default -> compact.add(option);
            }
        }
        return List.copyOf(compact);
    }

    void releaseTask(SlotContext slot, JavacTask task) {
        boolean cleared = false;
        try {
            slot.context.clear();
            cleared = true;
        } catch (Throwable e) {
            // Context is corrupted (e.g. AP ExceptionInInitializerError left partial state).
            // Reset the slot so the next task gets a fresh context instead of a broken one.
            LOG.warning("[compiler] context_clear_failed resetting slot reason=" + e.getMessage());
        }
        cleanupTask(task);
        if (cleared) {
            slot.inUse = false;
        } else {
            slot.reset(); // creates new ReusableContext, sets inUse=false
        }
    }

    void cleanupTask(JavacTask task) {
        try {
            var m = JavacTaskImpl.class.getDeclaredMethod("cleanup");
            m.setAccessible(true);
            m.invoke(task);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            LOG.fine("Task cleanup failed: " + e.getMessage());
        }
    }

    static class Borrow implements AutoCloseable {
        final ReusableCompiler compiler;
        final SlotContext slot;
        final JavacTask task;
        boolean closed;

        Borrow(ReusableCompiler compiler, SlotContext slot, JavacTask task) {
            this.compiler = compiler;
            this.slot = slot;
            this.task = task;
        }

        @Override
        public void close() {
            if (closed) return;
            if (slot == null) {
                compiler.cleanupTask(task);
            } else {
                compiler.releaseTask(slot, task);
            }
            closed = true;
        }
    }

    static class ReusableContext extends Context implements TaskListener {

        ReusableContext() {
            super();
            put(Log.logKey, ReusableLog.factory);
            put(JavaCompiler.compilerKey, ReusableJavaCompiler.factory);
        }

        ReusableContext(List<String> arguments) {
            this();
        }

        void clear() {
            drop(Arguments.argsKey);
            drop(Options.optionsKey);
            dropStaticContextKey("com.sun.tools.javac.code.Source", "sourceKey");
            dropStaticContextKey("com.sun.tools.javac.jvm.Target", "targetKey");
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
            dropByClassName("com.sun.tools.javac.platform.PlatformDescription");

            if (ht.get(Log.logKey) instanceof ReusableLog) {
                // log already inited - not first round
                Log.instance(this).clear();
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

        private void dropStaticContextKey(String ownerClassName, String fieldName) {
            try {
                var owner = Class.forName(ownerClassName);
                var field = owner.getDeclaredField(fieldName);
                field.setAccessible(true);
                var key = field.get(null);
                if (key instanceof Context.Key<?> contextKey) {
                    ht.remove(contextKey);
                }
            } catch (ReflectiveOperationException ignored) {
                // No-op: javac internals can move between JDK versions.
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

            public void clear() {
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

            public void clear() {
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
