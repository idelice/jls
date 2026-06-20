// Forked from JavacTaskImpl / JavacTaskPool (OpenJDK)
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
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.DefinedBy;
import com.sun.tools.javac.util.DefinedBy.Api;
import com.sun.tools.javac.util.Log;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

/**
 * Fresh-context-per-compile wrapper. Each compile() call creates a new
 * ReusableContext, guaranteeing zero state leakage between compilations.
 * The ReusableLog and ReusableJavaCompiler factories are registered in the
 * context so javac uses our custom implementations for diagnostic capture.
 */
class ReusableCompiler {

    private static final Logger LOG = Logger.getLogger("main");
    private static final JavacTool systemProvider = JavacTool.create();

    private ReusableContext currentContext = new ReusableContext();

    /**
     * Execute a compilation inside the pool. The worker receives a valid JavacTask
     * and must do all parse/analyze work. After the worker returns, the results
     * (Trees, Elements, Types, roots) remain valid until the next compile() call.
     */
    <T> T compile(
            JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            List<String> options,
            Collection<? extends JavaFileObject> compilationUnits,
            Function<JavacTask, T> worker) {
        // Fresh context every compile — zero state leakage between compilations
        currentContext = new ReusableContext();
        LOG.info("[context] compile() — fresh context, calling getTask()");
        try {
            var task = (JavacTaskImpl) systemProvider.getTask(
                    null, fileManager, diagnosticListener, options, List.of(), compilationUnits, currentContext);
            task.addTaskListener(currentContext);
            var log = Log.instance(currentContext);
            if (log instanceof ReusableContext.ReusableLog rl) {
                rl.setDiagListener(diagnosticListener);
            }
            LOG.info("[context] compile() — getTask() succeeded, worker starting");
            return worker.apply(task);
        } catch (Throwable e) {
            LOG.warning("[context] compile() — failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            throw new RuntimeException("Compilation failed: " + e.getMessage(), e);
        }
    }

    static class ReusableContext extends Context implements TaskListener {

        ReusableContext() {
            super();
            put(Log.logKey, ReusableLog.factory);
            put(JavaCompiler.compilerKey, ReusableJavaCompiler.factory);
        }

        @Override
        @DefinedBy(Api.COMPILER_TREE)
        public void finished(TaskEvent e) {
            // do nothing
        }

        @Override
        @DefinedBy(Api.COMPILER_TREE)
        public void started(TaskEvent e) {}

        <T> void drop(Key<T> k) { ht.remove(k); }
        <T> void drop(Class<T> c) { ht.remove(key(c)); }

        static class ReusableJavaCompiler extends JavaCompiler {
            static final Factory<JavaCompiler> factory = ReusableJavaCompiler::new;

            ReusableJavaCompiler(Context context) { super(context); }

            @Override
            public void close() {}

            void clear() {
                newRound();
                // Null procEnvImpl to force fresh AP initialization each time.
                // Without a JDK-level reset() method, this is the safest approach:
                // annotation processors are rediscovered, but incremental Lombok
                // compilation (fewer files) keeps overall compile time low.
                try {
                    var f = JavaCompiler.class.getDeclaredField("procEnvImpl");
                    f.setAccessible(true);
                    f.set(this, null);
                } catch (ReflectiveOperationException e) {
                    LOG.warning("[compiler] Failed to null procEnvImpl: " + e.getMessage());
                }
            }

            @Override
            protected void checkReusable() {}
        }

        static class ReusableLog extends Log {
            static final Factory<Log> factory = ReusableLog::new;
            Context context;

            ReusableLog(Context context) {
                super(context);
                this.context = context;
            }

            @SuppressWarnings("unchecked")
            void setDiagListener(DiagnosticListener<? super JavaFileObject> listener) {
                this.diagListener = (DiagnosticListener<? super JavaFileObject>) listener;
            }

            public void clear() {
                super.clear();
            }
        }
    }
}
