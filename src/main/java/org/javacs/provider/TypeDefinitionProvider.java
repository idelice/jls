package org.javacs.provider;

import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.SourceFileObject;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.Location;
import org.javacs.navigation.NavigationHelper;

/** Resolves the semantic type at a cursor, then navigates to that type's declaration. */
public final class TypeDefinitionProvider {
    public static final List<Location> NOT_SUPPORTED = List.of();

    private final CompilerProvider compiler;
    private final TypeIndexRouter typeIndex;
    private final Path file;
    private final int line;
    private final int column;

    public TypeDefinitionProvider(
            CompilerProvider compiler,
            TypeIndexRouter typeIndex,
            Path file,
            int line,
            int column) {
        this.compiler = compiler;
        this.typeIndex = typeIndex == null ? TypeIndexRouter.EMPTY : typeIndex;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<Location> find() {
        try (var task = compiler.compile(file)) {
            var path = NavigationHelper.findPath(task, file, line, column);
            if (path == null) return NOT_SUPPORTED;
            var element = NavigationHelper.findElement(task, path);
            var type = semanticType(task, path, element);
            var location = locate(task, type);
            return location == null ? NOT_SUPPORTED : List.of(location);
        }
    }

    private TypeMirror semanticType(CompileTask task, TreePath path, Element element) {
        if (element != null && element.getKind() == ElementKind.CONSTRUCTOR) {
            return element.getEnclosingElement().asType();
        }

        var type = task.trees.getTypeMirror(path);
        if (type instanceof ExecutableType executable) {
            type = executable.getReturnType();
        }
        if (type == null || type.getKind() == TypeKind.NONE || type.getKind() == TypeKind.ERROR) {
            if (element instanceof ExecutableElement executable) {
                type = executable.getReturnType();
            } else if (element != null) {
                type = element.asType();
            }
        }
        while (type instanceof ArrayType array) {
            type = array.getComponentType();
        }
        return type;
    }

    private Location locate(CompileTask task, TypeMirror type) {
        if (type == null) return null;
        var element = task.types.asElement(type);
        var directPath = element == null ? null : task.trees.getPath(element);
        if (directPath != null) {
            var location = FindHelper.location(task, directPath, element.getSimpleName());
            if (location != null) return location;
        }
        if (element instanceof TypeElement declared) return locateType(task, declared);
        if (type instanceof TypeVariable variable) return locate(task, variable.getUpperBound());
        if (type instanceof WildcardType wildcard) {
            return locate(task, wildcard.getExtendsBound());
        }
        if (type instanceof IntersectionType intersection) {
            for (var bound : intersection.getBounds()) {
                var location = locate(task, bound);
                if (location != null) return location;
            }
        }
        return null;
    }

    private Location locateType(CompileTask task, TypeElement type) {
        var qualifiedName = type.getQualifiedName().toString();
        var indexed = typeIndex.typeInfo(qualifiedName).orElse(null);
        if (indexed != null) {
            var indexedLocation = indexed.declarationLocation().orElse(null);
            if (indexedLocation != null) return indexedLocation;
            if (indexed.sourcePath != null) {
                var location = locateInParse(compiler.parse(indexed.sourcePath), qualifiedName);
                if (location != null) return location;
            }
        }

        var sourceOwner = outermostTypeName(type);
        var source = compiler.findAnywhere(qualifiedName)
                .or(() -> qualifiedName.equals(sourceOwner)
                        ? java.util.Optional.empty()
                        : compiler.findAnywhere(sourceOwner));
        if (source.isPresent()) {
            var location = locateInParse(compiler.parse(source.get()), qualifiedName);
            if (location != null) return location;
        }

        var decompiled = compiler.decompileClass(qualifiedName)
                .or(() -> qualifiedName.equals(sourceOwner)
                        ? java.util.Optional.empty()
                        : compiler.decompileClass(sourceOwner))
                .or(() -> typeIndex.externalDecompiledSourcePath(qualifiedName));
        if (decompiled.isPresent()) {
            var parse = compiler.parse(new SourceFileObject(decompiled.get()));
            var location = locateInParse(parse, qualifiedName);
            if (location != null) return location;
        }

        return null;
    }

    private static Location locateInParse(org.javacs.ParseTask parse, String qualifiedName) {
        var tree = FindHelper.findType(parse, qualifiedName);
        if (tree == null) return null;
        var path = TreePath.getPath(parse.root(), tree);
        if (path == null) return null;
        return FindHelper.location(parse, path, tree.getSimpleName());
    }

    private static String outermostTypeName(TypeElement type) {
        var outermost = type;
        for (var owner = type.getEnclosingElement(); owner instanceof TypeElement enclosing;
                owner = enclosing.getEnclosingElement()) {
            outermost = enclosing;
        }
        return outermost.getQualifiedName().toString();
    }
}
