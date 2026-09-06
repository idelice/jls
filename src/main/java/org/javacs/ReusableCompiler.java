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

import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.api.*;
import com.sun.tools.javac.code.Preview;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.main.Arguments;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.platform.PlatformDescription;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.util.*;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import javax.tools.*;

/** JDK 25 context cleanup, adapted to an explicit borrow covering all Trees/Elements consumers. */
final class ReusableCompiler {
    private static final Logger LOG = Logger.getLogger("main");
    private static final JavacTool JAVAC = JavacTool.create();
    private static final Method CLEANUP = cleanupMethod();
    private static final AtomicLong IDS = new AtomicLong();
    private static final LinkedHashMap<ReusableCompiler, Boolean> IDLE = new LinkedHashMap<>();
    private static final int MAX_IDLE_CONTEXTS = 4;
    private static final int MAX_USES = 64;
    private ReusableContext context;
    private boolean checkedOut;

    /**
     * @param oneShot the caller consumes the result once (a workspace-wide scan). A context created
     *     for a one-shot borrow is retired instead of competing for the idle pool with the modules
     *     the user is editing. An already warm context stays warm.
     */
    Borrow borrow(JavaFileManager files, DiagnosticListener<? super JavaFileObject> diagnostics,
            List<String> options, Collection<? extends JavaFileObject> sources, boolean oneShot) {
        if (checkedOut) throw new IllegalStateException("Compiler is already in use");
        if (!options.contains("-proc:none") || options.stream().anyMatch(option ->
                option.startsWith("-proc:") && !option.equals("-proc:none"))) {
            throw new IllegalArgumentException("Semantic analysis requires -proc:none");
        }
        synchronized (IDLE) { IDLE.remove(this); }
        var revision = FileStore.contentRevision();
        var reused = context != null && context.options.equals(options) && context.stillValid(sources, revision);
        if (!reused) {
            discard("source_changed");
            context = new ReusableContext(options);
        }
        context.revision = revision;
        checkedOut = true;
        try {
            var task = (JavacTaskImpl) JAVAC.getTask(null, files, diagnostics, options, null, sources, context);
            task.setProcessors(List.of());
            task.addTaskListener(context);
            context.uses++;
            context.capturePlatform();
            return new Borrow(task, context, reused, oneShot && !reused);
        } catch (RuntimeException | Error failure) {
            checkedOut = false;
            discard();
            throw failure;
        }
    }

    boolean isWarm() {
        return context != null;
    }

    void discard() {
        discard("owner_request");
    }

    void discard(String reason) {
        if (checkedOut) throw new IllegalStateException("Cannot discard a borrowed compiler");
        synchronized (IDLE) { IDLE.remove(this); }
        if (context != null) {
            LOG.info("[analysis] retire context=" + context.id + " reason=" + reason
                    + " uses=" + context.uses
                    + " release_managers=" + context.platforms.size() + " polluted=" + context.polluted);
            context.dispose();
            context = null;
        }
    }

    private void retain() {
        synchronized (IDLE) {
            IDLE.remove(this);
            IDLE.put(this, Boolean.TRUE);
            while (IDLE.size() > MAX_IDLE_CONTEXTS) IDLE.firstEntry().getKey().discard("idle_evicted");
        }
    }

    private static Method cleanupMethod() {
        try {
            var method = JavacTaskImpl.class.getDeclaredMethod("cleanup");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    final class Borrow implements AutoCloseable {
        final JavacTaskImpl task;
        final boolean reused;
        private final ReusableContext borrowed;
        private final boolean oneShot;
        private boolean valid = true;
        private boolean closed;

        Borrow(JavacTaskImpl task, ReusableContext borrowed, boolean reused, boolean oneShot) {
            this.task = task;
            this.borrowed = borrowed;
            this.reused = reused;
            this.oneShot = oneShot;
        }

        long id() { return borrowed.id; }
        void invalidate() { valid = false; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try {
                borrowed.clear();
                CLEANUP.invoke(task);
            } catch (ReflectiveOperationException | RuntimeException | AssertionError | LinkageError failure) {
                valid = false;
                LOG.warning("[analysis] cleanup_failed context=" + id() + " cause=" + failure);
            } finally {
                checkedOut = false;
                if (!valid) discard("analysis_failed");
                else if (oneShot) discard("one_shot_scan");
                else if (borrowed.polluted) discard("polluted");
                else if (borrowed.uses >= MAX_USES) discard("use_limit");
                else retain();
            }
        }
    }

    private static final class ReusableContext extends Context implements TaskListener {
        final long id = IDS.incrementAndGet();
        final List<String> options;
        final List<CompilationUnitTree> roots = new ArrayList<>();
        final List<PlatformDescription> platforms = new ArrayList<>();
        /** Every source this context has entered, including files javac loaded implicitly. */
        final Set<Path> parsed = new HashSet<>();
        long revision;
        int uses;
        boolean polluted;

        ReusableContext(List<String> options) {
            this.options = List.copyOf(options);
            put(Log.logKey, (Factory<Log>) ReusableLog::new);
            put(JavaCompiler.compilerKey, (Factory<JavaCompiler>) ReusableJavaCompiler::new);
        }

        void capturePlatform() {
            var platform = get(PlatformDescription.class);
            if (platform != null && !platforms.contains(platform)) platforms.add(platform);
        }

        /**
         * A retained source symbol is correct while its file is unchanged, and also when this task
         * parses the file again: javac re-enters the declarations it parses. Only a changed file
         * that stays implicit is stale, because javac never re-lists a completed package.
         *
         * <p>The re-entry invariant is javac implementation behaviour, verified against JDK 25.0.2.
         * If a future compiler stops re-entering re-parsed sources, reuse must be narrowed to
         * borrows whose requested files are unchanged.
         */
        boolean stillValid(Collection<? extends JavaFileObject> requested, long revision) {
            if (revision == this.revision) return true;
            var changed = FileStore.changedSince(this.revision);
            if (changed.isEmpty()) return true;
            var reparsed = new HashSet<Path>();
            for (var source : requested) {
                var path = filePath(source.toUri());
                if (path != null) reparsed.add(path);
            }
            for (var file : changed) {
                if (parsed.contains(file) && !reparsed.contains(file)) {
                    LOG.info("[analysis] retire context=" + id + " reason=implicit_source_changed file="
                            + file.getFileName());
                    return false;
                }
            }
            return true;
        }

        private static Path filePath(java.net.URI uri) {
            if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
            return Path.of(uri).toAbsolutePath().normalize();
        }

        void clear() {
            capturePlatform();
            var files = get(JavaFileManager.class);
            polluted |= files != null && files.hasLocation(StandardLocation.PATCH_MODULE_PATH);
            new TreeScanner<Void, Void>() {
                @Override public Void scan(Tree tree, Void unused) {
                    if (tree instanceof com.sun.tools.javac.tree.JCTree.LetExpr expression) {
                        scan(expression.defs, unused);
                        return scan(expression.expr, unused);
                    }
                    return super.scan(tree, unused);
                }
                @Override public Void visitClass(ClassTree tree, Void unused) {
                    // Source symbols stay in the symbol table: this context is only reused while
                    // every source is unchanged, and javac re-enters the sources it parses again.
                    var symbol = ((JCClassDecl) tree).sym;
                    if (symbol != null) {
                        polluted |= symbol.flatName().toString().startsWith("java.");
                        if (symbol.type instanceof com.sun.tools.javac.code.Type.ClassType type
                                && type.supertype_field != null) {
                            var parent = type.supertype_field.tsym;
                            polluted |= parent != null && parent.flatName().toString().startsWith("java.")
                                    && parent.kind != com.sun.tools.javac.code.Kinds.Kind.TYP;
                        }
                    }
                    return super.visitClass(tree, unused);
                }
            }.scan(roots, null);
            roots.clear();
            drop(Arguments.argsKey);
            drop(DiagnosticListener.class);
            drop(Log.outKey);
            drop(Log.errKey);
            drop(JavaFileManager.class);
            drop(JavacTask.class);
            drop(JavacTrees.class);
            drop(JavacElements.class);
            drop(PlatformDescription.class);
            Log.instance(this).clear();
            Enter.instance(this).newRound();
            ((ReusableJavaCompiler) JavaCompiler.instance(this)).newRound();
            Types.instance(this).newRound();
            Check.instance(this).newRound();
            Check.instance(this).clear();
            Preview.instance(this).clear();
            Modules.instance(this).newRound();
            Annotate.instance(this).newRound();
            CompileStates.instance(this).clear();
            MultiTaskListener.instance(this).clear();
            Options.instance(this).clear();
        }

        void dispose() {
            capturePlatform();
            // Warm components can retain any release file manager. Close them only on retirement.
            for (var platform : platforms) {
                try { platform.close(); } catch (IOException e) { LOG.fine(e.getMessage()); }
            }
            platforms.clear();
            roots.clear();
            parsed.clear();
            ht.clear();
        }

        @Override public void finished(TaskEvent event) {
            if (event.getKind() != TaskEvent.Kind.PARSE) return;
            roots.add(event.getCompilationUnit());
            var path = filePath(event.getCompilationUnit().getSourceFile().toUri());
            if (path != null) parsed.add(path);
        }
        private <T> void drop(Key<T> key) { ht.remove(key); }
        private <T> void drop(Class<T> type) { ht.remove(key(type)); }
    }

    private static final class ReusableJavaCompiler extends JavaCompiler {
        ReusableJavaCompiler(Context context) { super(context); }
        @Override public void close() { }
        @Override protected void checkReusable() { }
    }

    private static final class ReusableLog extends Log {
        private final Context context;
        ReusableLog(Context context) { super(context); this.context = context; }
        @Override public void clear() {
            super.clear();
            diagListener = diagnostic -> {
                var listener = context.get(DiagnosticListener.class);
                if (listener != null) listener.report(diagnostic);
            };
        }
    }
}
