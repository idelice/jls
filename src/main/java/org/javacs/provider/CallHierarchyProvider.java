package org.javacs.provider;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.lsp.CallHierarchyIncomingCall;
import org.javacs.lsp.CallHierarchyItem;
import org.javacs.lsp.CallHierarchyOutgoingCall;
import org.javacs.lsp.Range;
import org.javacs.lsp.SymbolKind;
import org.javacs.navigation.FindReferences;
import org.javacs.navigation.NavigationHelper;

/** Builds call hierarchy items and resolves incoming/outgoing calls, verified by javac. */
public final class CallHierarchyProvider {
    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;
    private final Function<Path, CompilerProvider> compilerForFile;
    private final Consumer<Path[]> batchResolver;
    private final Runnable includeReferenceSources;

    public CallHierarchyProvider(
            CompilerProvider compiler,
            Function<Path, CompilerProvider> compilerForFile,
            Consumer<Path[]> batchResolver,
            Runnable includeReferenceSources) {
        this.compiler = compiler;
        this.compilerForFile = compilerForFile;
        this.batchResolver = batchResolver;
        this.includeReferenceSources = includeReferenceSources;
    }

    public Optional<List<CallHierarchyItem>> prepare(Path file, int line, int column) {
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (!(element instanceof ExecutableElement method)) return Optional.empty();
            if (!(method.getEnclosingElement() instanceof TypeElement owner)) {
                return Optional.empty();
            }
            var path = task.trees.getPath(method);
            if (path == null) return Optional.empty();
            var isCtor = method.getKind() == ElementKind.CONSTRUCTOR;
            var displayName = isCtor ? owner.getSimpleName().toString() : method.getSimpleName().toString();
            var location = FindHelper.location(task, path, displayName);
            if (location == null || location.uri == null) return Optional.empty();
            var item = new CallHierarchyItem();
            item.name = displayName;
            item.kind = isCtor ? SymbolKind.Constructor : SymbolKind.Method;
            item.detail = owner.getQualifiedName().toString();
            item.uri = location.uri;
            item.range = location.range;
            item.selectionRange = location.range;
            item.data = methodData(task, owner, method);
            return Optional.of(List.of(item));
        } catch (RuntimeException e) {
            LOG.warning(String.format(
                    "[call-hierarchy] prepare_error file=%s message=%s", file.getFileName(), e.getMessage()));
            return Optional.empty();
        }
    }

    public List<CallHierarchyIncomingCall> incomingCalls(CallHierarchyItem item) {
        var data = MethodData.parse(dataString(item.data));
        if (data == null) return List.of();
        includeReferenceSources.run();
        var file = Path.of(item.uri);
        var memberName = data.methodName.equals("<init>") ? simpleName(data.className) : data.methodName;
        try {
            var searchNames = incomingSearchNames(file, data.className, memberName);
            var candidates = new LinkedHashSet<Path>();
            for (var cn : searchNames) {
                for (var f : compilerForFile.apply(file).findMemberReferences(cn, memberName)) candidates.add(f);
            }
            if (candidates.isEmpty()) return List.of();
            candidates.add(file);
            return groupIncomingCalls(file, data, memberName, candidates.toArray(Path[]::new));
        } catch (RuntimeException e) {
            LOG.warning(String.format(
                    "[call-hierarchy] incoming_error name=%s message=%s", data.methodName, e.getMessage()));
            return List.of();
        }
    }

    public List<CallHierarchyOutgoingCall> outgoingCalls(CallHierarchyItem item) {
        var data = MethodData.parse(dataString(item.data));
        if (data == null) return List.of();
        var file = Path.of(item.uri);
        try (var task = compilerForFile.apply(file).compile(file)) {
            var method = FindHelper.findMethod(task, data.className, data.methodName, data.erasedParameterTypes);
            if (method == null) return List.of();
            var methodPath = task.trees.getPath(method);
            if (methodPath == null) return List.of();
            var grouped = new LinkedHashMap<String, CallHierarchyOutgoingCall>();
            new OutgoingCallScanner(task, grouped).scan(methodPath, null);
            return new ArrayList<>(grouped.values());
        } catch (RuntimeException e) {
            LOG.warning(String.format(
                    "[call-hierarchy] outgoing_error name=%s message=%s", data.methodName, e.getMessage()));
            return List.of();
        }
    }

    /** Collect the declaring class plus supertypes that declare the same method, matching ReferenceProvider. */
    private LinkedHashSet<String> incomingSearchNames(Path file, String className, String memberName) {
        var names = new LinkedHashSet<String>();
        names.add(className);
        try (var task = compilerForFile.apply(file).compile(file)) {
            var owner = task.elements.getTypeElement(className);
            if (owner != null) {
                for (var st : NavigationHelper.findDeclaringSupertypes(task.types, owner, memberName)) {
                    names.add(st.getQualifiedName().toString());
                }
            }
        } catch (RuntimeException e) {
            LOG.fine(String.format("[call-hierarchy] supertype_lookup_failed name=%s", className));
        }
        return names;
    }

    private List<CallHierarchyIncomingCall> groupIncomingCalls(
            Path file, MethodData data, String memberName, Path[] candidates) {
        batchResolver.accept(candidates);
        var grouped = new LinkedHashMap<String, CallHierarchyIncomingCall>();
        try (var task = compilerForFile.apply(file).compileScan(candidates)) {
            var target = FindHelper.findMethod(task, data.className, data.methodName, data.erasedParameterTypes);
            if (target == null) return List.of();
            for (var root : task.roots) {
                var paths = new ArrayList<TreePath>();
                new FindReferences(task, target).scan(root, paths);
                for (var refPath : paths) {
                    var enclosing = enclosingMethodPath(refPath);
                    if (enclosing == null) continue;
                    var refLocation = FindHelper.location(task, refPath);
                    if (refLocation == null) continue;
                    addIncomingCall(task, grouped, enclosing, refLocation.range);
                }
            }
        }
        return new ArrayList<>(grouped.values());
    }

    private void addIncomingCall(
            CompileTask task,
            LinkedHashMap<String, CallHierarchyIncomingCall> grouped,
            TreePath methodPath,
            Range callRange) {
        var element = task.trees.getElement(methodPath);
        if (!(element instanceof ExecutableElement caller)) return;
        if (!(caller.getEnclosingElement() instanceof TypeElement owner)) return;
        var isCtor = caller.getKind() == ElementKind.CONSTRUCTOR;
        var displayName = isCtor ? owner.getSimpleName().toString() : caller.getSimpleName().toString();
        var location = FindHelper.location(task, methodPath, displayName);
        if (location == null || location.uri == null) return;
        var key = location.uri + ":" + location.range;
        var call = grouped.get(key);
        if (call == null) {
            var item = new CallHierarchyItem();
            item.name = displayName;
            item.kind = isCtor ? SymbolKind.Constructor : SymbolKind.Method;
            item.detail = owner.getQualifiedName().toString();
            item.uri = location.uri;
            item.range = location.range;
            item.selectionRange = location.range;
            item.data = methodData(task, owner, caller);
            call = new CallHierarchyIncomingCall();
            call.from = item;
            call.fromRanges = new ArrayList<>();
            grouped.put(key, call);
        }
        call.fromRanges.add(callRange);
    }

    /** Scans a method body for invocations, grouping unique callees with their call-site ranges. */
    private final class OutgoingCallScanner extends TreePathScanner<Void, Void> {
        private final CompileTask task;
        private final LinkedHashMap<String, CallHierarchyOutgoingCall> grouped;

        OutgoingCallScanner(CompileTask task, LinkedHashMap<String, CallHierarchyOutgoingCall> grouped) {
            this.task = task;
            this.grouped = grouped;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree t, Void unused) {
            record();
            return super.visitMethodInvocation(t, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree t, Void unused) {
            record();
            return super.visitNewClass(t, unused);
        }

        private void record() {
            var callSitePath = getCurrentPath();
            var callee = task.trees.getElement(callSitePath);
            if (!(callee instanceof ExecutableElement method)) return;
            if (!(method.getEnclosingElement() instanceof TypeElement owner)) return;
            var declPath = task.trees.getPath(method);
            if (declPath == null) return; // callee has no source (JDK/dependency without source) — skip
            var callLocation = FindHelper.location(task, callSitePath);
            if (callLocation == null) return;
            var isCtor = method.getKind() == ElementKind.CONSTRUCTOR;
            var displayName = isCtor ? owner.getSimpleName().toString() : method.getSimpleName().toString();
            var location = FindHelper.location(task, declPath, displayName);
            if (location == null || location.uri == null) return;
            var key = location.uri + ":" + location.range;
            var call = grouped.get(key);
            if (call == null) {
                var item = new CallHierarchyItem();
                item.name = displayName;
                item.kind = isCtor ? SymbolKind.Constructor : SymbolKind.Method;
                item.detail = owner.getQualifiedName().toString();
                item.uri = location.uri;
                item.range = location.range;
                item.selectionRange = location.range;
                item.data = methodData(task, owner, method);
                call = new CallHierarchyOutgoingCall();
                call.to = item;
                call.fromRanges = new ArrayList<>();
                grouped.put(key, call);
            }
            call.fromRanges.add(callLocation.range);
        }
    }

    private static TreePath enclosingMethodPath(TreePath path) {
        for (var p = path; p != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof MethodTree) return p;
        }
        return null;
    }

    /** Encode a method as "com.Foo#bar(String,int)" for round-tripping through the client. */
    private static String methodData(CompileTask task, TypeElement owner, ExecutableElement method) {
        var params = String.join(",", FindHelper.erasedParameterTypes(task, method));
        return owner.getQualifiedName() + "#" + method.getSimpleName() + "(" + params + ")";
    }

    /** Parsed "com.Foo#bar(String,int)" method reference. */
    private record MethodData(String className, String methodName, String[] erasedParameterTypes) {
        static MethodData parse(String data) {
            if (data == null) return null;
            var hash = data.indexOf('#');
            var open = data.indexOf('(', hash);
            var close = data.lastIndexOf(')');
            if (hash < 0 || open < 0 || close < open) return null;
            var className = data.substring(0, hash);
            var methodName = data.substring(hash + 1, open);
            var paramString = data.substring(open + 1, close).trim();
            var params = paramString.isEmpty() ? new String[0] : paramString.split(",");
            return new MethodData(className, methodName, params);
        }
    }

    private static String dataString(Object data) {
        if (data == null) return null;
        if (data instanceof com.google.gson.JsonElement json) {
            return json.isJsonPrimitive() ? json.getAsString() : null;
        }
        return data.toString();
    }

    private static String simpleName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }
}
