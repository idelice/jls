package org.javacs.navigation;

import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.JavaFileObject;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.SourceFileObject;
import org.javacs.lsp.Location;

public class DefinitionProvider {
    private final CompilerProvider compiler;
    private final Path file;
    private final int line, column;

    public static final List<Location> NOT_SUPPORTED = List.of();

    public DefinitionProvider(CompilerProvider compiler, Path file, int line, int column) {
        this.compiler = compiler;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<Location> find() {
        var tStart = System.nanoTime();
        long compileMs = 0;
        long resolveMs = 0;
        long remoteCompileMs = 0;
        String kind = "unknown";
        List<Location> result = NOT_SUPPORTED;

        var compileStart = System.nanoTime();
        try (var task = compiler.compile(file)) {
            compileMs = (System.nanoTime() - compileStart) / 1_000_000;
            var resolveStart = System.nanoTime();
            var element = NavigationHelper.findElement(task, file, line, column);
            resolveMs = (System.nanoTime() - resolveStart) / 1_000_000;
            if (element == null) {
                kind = "no-element";
                result = NOT_SUPPORTED;
            } else if (element.asType().getKind() == TypeKind.ERROR) {
                task.close();
                kind = "error";
                result = findError(element);
            } else if (NavigationHelper.isLocal(element)) {
                kind = "local";
                result = findDefinitions(task, element);
            } else {
                var className = className(element);
                if (className.isEmpty()) {
                    kind = "no-class";
                    result = NOT_SUPPORTED;
                } else {
                    var otherFile = compiler.findAnywhere(className);
                    if (otherFile.isEmpty()) {
                        kind = "not-found";
                        result = List.of();
                    } else if (otherFile.get().toUri().equals(file.toUri())) {
                        kind = "same-file";
                        result = findDefinitions(task, element);
                    } else {
                        task.close();
                        var remote = findRemoteDefinitions(otherFile.get());
                        remoteCompileMs = remote.compileMs;
                        result = remote.locations;
                        kind = "remote";
                    }
                }
            }
        } finally {
            var totalMs = (System.nanoTime() - tStart) / 1_000_000;
            var size = result == null ? 0 : result.size();
            LOG.info(
                    String.format(
                            "Definition %s:%d:%d kind=%s total=%dms compile=%dms resolve=%dms remoteCompile=%dms results=%d",
                            file.getFileName(),
                            line,
                            column,
                            kind,
                            totalMs,
                            compileMs,
                            resolveMs,
                            remoteCompileMs,
                            size));
        }
        return result;
    }

    private List<Location> findError(Element element) {
        var name = element.getSimpleName();
        if (name == null) return NOT_SUPPORTED;
        var parent = element.getEnclosingElement();
        if (!(parent instanceof TypeElement)) return NOT_SUPPORTED;
        var type = (TypeElement) parent;
        var className = type.getQualifiedName().toString();
        var memberName = name.toString();
        return findAllMembers(className, memberName);
    }

    private List<Location> findAllMembers(String className, String memberName) {
        var otherFile = compiler.findAnywhere(className);
        if (otherFile.isEmpty()) return List.of();
        var fileAsSource = new SourceFileObject(file);
        var sources = List.of(fileAsSource, otherFile.get());
        if (otherFile.get().toString().equals(file.toUri())) {
            sources = List.of(fileAsSource);
        }
        var locations = new ArrayList<Location>();
        try (var task = compiler.compile(sources)) {
            var trees = Trees.instance(task.task);
            var elements = task.task.getElements();
            var parentClass = elements.getTypeElement(className);
            for (var member : elements.getAllMembers(parentClass)) {
                if (!member.getSimpleName().contentEquals(memberName)) continue;
                var path = trees.getPath(member);
                if (path == null) continue;
                var location = FindHelper.location(task, path, memberName);
                locations.add(location);
            }
        }
        return locations;
    }

    private String className(Element element) {
        while (element != null) {
            if (element instanceof TypeElement) {
                var type = (TypeElement) element;
                return type.getQualifiedName().toString();
            }
            element = element.getEnclosingElement();
        }
        return "";
    }

    private DefinitionResult findRemoteDefinitions(JavaFileObject otherFile) {
        var t0 = System.nanoTime();
        try (var task = compiler.compile(List.of(new SourceFileObject(file), otherFile))) {
            var element = NavigationHelper.findElement(task, file, line, column);
            var locations = findDefinitions(task, element);
            var compileMs = (System.nanoTime() - t0) / 1_000_000;
            return new DefinitionResult(locations, compileMs);
        }
    }

    private List<Location> findDefinitions(CompileTask task, Element element) {
        var trees = Trees.instance(task.task);
        var path = trees.getPath(element);
        if (path == null) {
            return List.of();
        }
        var name = element.getSimpleName();
        if (name.contentEquals("<init>")) name = element.getEnclosingElement().getSimpleName();
        return List.of(FindHelper.location(task, path, name));
    }

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("main");

    private static final class DefinitionResult {
        final List<Location> locations;
        final long compileMs;

        DefinitionResult(List<Location> locations, long compileMs) {
            this.locations = locations;
            this.compileMs = compileMs;
        }
    }
}
