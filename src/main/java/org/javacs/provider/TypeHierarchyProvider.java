package org.javacs.provider;

import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.Location;
import org.javacs.lsp.SymbolKind;
import org.javacs.lsp.TypeHierarchyItem;
import org.javacs.navigation.NavigationHelper;

/** Builds type hierarchy items, using javac to prepare and the workspace index to relate. */
public final class TypeHierarchyProvider {
    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;
    private final Function<Path, TypeIndexRouter> typeIndexForFile;
    private final Function<Path, CompilerProvider> compilerForFile;

    public TypeHierarchyProvider(
            CompilerProvider compiler,
            Function<Path, TypeIndexRouter> typeIndexForFile,
            Function<Path, CompilerProvider> compilerForFile) {
        this.compiler = compiler;
        this.typeIndexForFile = typeIndexForFile;
        this.compilerForFile = compilerForFile;
    }

    public Optional<List<TypeHierarchyItem>> prepare(Path file, int line, int column) {
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            var type = enclosingType(element);
            if (type == null) return Optional.empty();
            var path = task.trees.getPath(type);
            if (path == null) return Optional.empty();
            var item = typeHierarchyItem(
                    type.getSimpleName().toString(),
                    typeSymbolKind(type),
                    type.getQualifiedName().toString(),
                    FindHelper.location(task, path, type.getSimpleName()));
            if (item == null) return Optional.empty();
            return Optional.of(List.of(item));
        } catch (RuntimeException e) {
            LOG.warning(String.format(
                    "[type-hierarchy] prepare_error file=%s message=%s", file.getFileName(), e.getMessage()));
            return Optional.empty();
        }
    }

    public List<TypeHierarchyItem> supertypes(TypeHierarchyItem item) {
        return related(item, true);
    }

    public List<TypeHierarchyItem> subtypes(TypeHierarchyItem item) {
        return related(item, false);
    }

    private List<TypeHierarchyItem> related(TypeHierarchyItem item, boolean supertypes) {
        var qualifiedName = dataString(item.data);
        if (qualifiedName == null) return List.of();
        var file = Path.of(item.uri);
        try {
            var index = typeIndexForFile.apply(file);
            var names = supertypes
                    ? index.directSupertypes(qualifiedName)
                    : index.workspaceSubTypes(qualifiedName);
            var items = new ArrayList<TypeHierarchyItem>();
            for (var name : names) {
                var built = typeHierarchyItemFor(name, file);
                if (built != null) items.add(built);
            }
            return items;
        } catch (RuntimeException e) {
            LOG.warning(String.format(
                    "[type-hierarchy] related_error name=%s supertypes=%s message=%s",
                    qualifiedName, supertypes, e.getMessage()));
            return List.of();
        }
    }

    /** Build a TypeHierarchyItem for a qualified name, preferring the index, falling back to parse. */
    private TypeHierarchyItem typeHierarchyItemFor(String qualifiedName, Path contextFile) {
        var index = typeIndexForFile.apply(contextFile);
        var info = index.typeInfo(qualifiedName).orElse(null);
        if (info != null && info.sourceUri != null && info.declarationRange != null) {
            return typeHierarchyItem(
                    info.simpleName,
                    info.kind == CompletionItemKind.Interface ? SymbolKind.Interface : SymbolKind.Class,
                    qualifiedName,
                    new Location(info.sourceUri, info.declarationRange));
        }
        // External/JDK type — locate via parse.
        var location = parseTypeLocation(contextFile, qualifiedName);
        if (location == null) return null;
        var simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        return typeHierarchyItem(simpleName, SymbolKind.Class, qualifiedName, location);
    }

    /** Parse the declaring source of an external type to get its declaration location. */
    private Location parseTypeLocation(Path contextFile, String qualifiedName) {
        var provider = compilerForFile.apply(contextFile);
        var found = provider.findAnywhere(qualifiedName);
        if (found.isEmpty()) return null;
        var parse = provider.parse(found.get());
        try {
            var classTree = FindHelper.findType(parse, qualifiedName);
            var path = TreePath.getPath(parse.root(), classTree);
            if (path == null) return null;
            return FindHelper.location(parse, path, classTree.getSimpleName());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static TypeHierarchyItem typeHierarchyItem(
            String name, int kind, String qualifiedName, Location location) {
        if (location == null || location.uri == null) return null;
        var item = new TypeHierarchyItem();
        item.name = name;
        item.kind = kind;
        item.detail = packageOf(qualifiedName);
        item.uri = location.uri;
        item.range = location.range;
        item.selectionRange = location.range;
        item.data = qualifiedName;
        return item;
    }

    private static TypeElement enclosingType(Element element) {
        for (var e = element; e != null; e = e.getEnclosingElement()) {
            if (e instanceof TypeElement type) return type;
        }
        return null;
    }

    private static int typeSymbolKind(TypeElement type) {
        return switch (type.getKind()) {
            case INTERFACE, ANNOTATION_TYPE -> SymbolKind.Interface;
            case ENUM -> SymbolKind.Enum;
            default -> SymbolKind.Class;
        };
    }

    private static String dataString(Object data) {
        if (data == null) return null;
        if (data instanceof com.google.gson.JsonElement json) {
            return json.isJsonPrimitive() ? json.getAsString() : null;
        }
        return data.toString();
    }

    private static String packageOf(String qualifiedName) {
        var dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedName.substring(0, dot);
    }
}
