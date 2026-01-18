package org.javacs.navigation;

import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.TypeElement;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.LombokHandler;
import org.javacs.LombokMetadataCache;
import org.javacs.lsp.Location;

public class ReferenceProvider {
    private final CompilerProvider compiler;
    private final LombokMetadataCache lombokCache;
    private final Path file;
    private final int line, column;

    public static final List<Location> NOT_SUPPORTED = List.of();

    public ReferenceProvider(
            CompilerProvider compiler, LombokMetadataCache lombokCache, Path file, int line, int column) {
        this.compiler = compiler;
        this.lombokCache = lombokCache;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<Location> find() {
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (element == null) return NOT_SUPPORTED;
            if (NavigationHelper.isType(element)) {
                var type = (TypeElement) element;
                var className = type.getQualifiedName().toString();
                task.close();
                return findTypeReferences(className);
            }
            if (NavigationHelper.isMember(element)) {
                var parentClass = (TypeElement) element.getEnclosingElement();
                var className = parentClass.getQualifiedName().toString();
                var memberName = element.getSimpleName().toString();
                if (memberName.equals("<init>")) {
                    memberName = parentClass.getSimpleName().toString();
                }
                task.close();
                if (element.getKind() == javax.lang.model.element.ElementKind.FIELD) {
                    var references = new ArrayList<Location>();
                    references.addAll(findFieldReferencesScoped(className, memberName));
                    references.addAll(
                            LombokHandler.findAccessorReferences(
                                    compiler, className, memberName, lombokCache));
                    return references;
                }
                return findMemberReferences(className, memberName);
            }
            if (NavigationHelper.isLocal(element)) {
                return findReferences(task);
            }
            return NOT_SUPPORTED;
        }
    }

    private List<Location> findTypeReferences(String className) {
        var files = compiler.findTypeReferences(className);
        if (files.length == 0) return List.of();
        try (var task = compiler.compile(files)) {
            return findReferences(task);
        }
    }

    private List<Location> findMemberReferences(String className, String memberName) {
        var files = compiler.findMemberReferences(className, memberName);
        if (files.length == 0) return List.of();
        try (var task = compiler.compile(files)) {
            return findReferences(task);
        }
    }

    private List<Location> findFieldReferencesScoped(String className, String memberName) {
        var files = compiler.findTypeReferences(className);
        var classFile = compiler.findTypeDeclaration(className);
        if (classFile != null && !classFile.equals(CompilerProvider.NOT_FOUND)) {
            var combined = new java.util.ArrayList<Path>();
            for (var f : files) {
                combined.add(f);
            }
            if (!combined.contains(classFile)) {
                combined.add(classFile);
            }
            files = combined.toArray(Path[]::new);
        }
        if (files.length == 0) return List.of();
        try (var task = compiler.compile(files)) {
            return findReferences(task);
        }
    }

    private List<Location> findReferences(CompileTask task) {
        var element = NavigationHelper.findElement(task, file, line, column);
        var paths = new ArrayList<TreePath>();
        for (var root : task.roots) {
            new FindReferences(task.task, element).scan(root, paths);
        }
        var locations = new ArrayList<Location>();
        for (var p : paths) {
            locations.add(FindHelper.location(task, p));
        }
        return locations;
    }
}
