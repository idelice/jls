package org.javacs.navigation;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.LombokAnnotations;
import org.javacs.ParseTask;
import org.javacs.ExternalTypeLookup;
import org.javacs.completion.TypeIndexRouter;
import org.javacs.completion.WorkspaceTypeIndex;
import org.javacs.lsp.Location;

/**
 * Source and decompiled lookup boundary for definition navigation.
 *
 * <p>DefinitionProvider decides what symbol should be resolved; this helper only turns that
 * already-resolved owner/member/type identity into source, attached-source, or decompiled
 * locations.
 */
final class DefinitionLocationLookup {
    record MemberTarget(String ownerType, String memberName, boolean method, List<Location> locations) {}

    private final CompilerProvider compiler;
    private final TypeIndexRouter completionIndex;
    private final ExternalTypeLookup typeLookup;
    private final ConcurrentHashMap<String, JavaFileObject> attachedExternalSources = new ConcurrentHashMap<>();

    DefinitionLocationLookup(CompilerProvider compiler, TypeIndexRouter completionIndex) {
        this.compiler = compiler;
        this.completionIndex = completionIndex == null ? TypeIndexRouter.EMPTY : completionIndex;
        this.typeLookup = new ExternalTypeLookup(compiler, this.completionIndex);
    }

    List<Location> findMethodLocations(String ownerType, String methodName, int argCount, List<String> argTypes) {
        var source = openTypeSource(ownerType);
        if (source.isPresent()) {
            var locations = findMethodLocations(source.get(), methodName, argCount, argTypes);
            if (!locations.isEmpty()) {
                return locations;
            }
        }
        var decompiled = openDecompiledTypeSource(ownerType);
        return decompiled.map(typeSource -> findMethodLocations(typeSource, methodName, argCount, argTypes))
                .orElseGet(List::of);
    }

    List<Location> findFieldLocations(String ownerType, String fieldName) {
        var directSourceLocations =
                Optional.ofNullable(attachedExternalSources.get(ownerType))
                        .map(sourceFile -> findFieldLocationsInFile(sourceFile, fieldName))
                        .orElseGet(() -> findFieldLocationsInDirectExternalSource(ownerType, fieldName));
        if (!directSourceLocations.isEmpty()) {
            return directSourceLocations;
        }
        var source = openTypeSource(ownerType);
        if (source.isPresent()) {
            var locations = findFieldLocations(source.get(), fieldName);
            if (!locations.isEmpty()) {
                return preferAttachedSource(ownerType, fieldName, locations);
            }
        }
        var decompiled = openDecompiledTypeSource(ownerType);
        return decompiled.map(typeSource -> findFieldLocations(typeSource, fieldName)).orElseGet(List::of);
    }

    List<Location> findAttachedSourceFieldLocations(String ownerType, String fieldName) {
        if (completionIndex.isWorkspaceOwnedType(ownerType, compiler)) {
            return List.of();
        }
        var source = typeLookup.findExternalSource(ownerType, "findAttachedSourceFieldLocations");
        if (source.isEmpty()) {
            return List.of();
        }
        var uri = source.get().toUri();
        if (uri == null || !"jar".equals(uri.getScheme())) {
            return List.of();
        }
        return findFieldLocationsInFile(source.get(), fieldName);
    }

    MemberTarget resolveFieldTarget(String ownerType, String fieldName, WorkspaceTypeIndex.Member member) {
        var targetOwner = indexedOwner(ownerType, member);
        if (member != null && member.backingFieldName != null && !member.backingFieldName.isBlank()) {
            return resolveLinkedField(linkedFieldOwner(targetOwner, member), fieldName, member.backingFieldName);
        }
        var fieldLocations = findFieldLocations(targetOwner, fieldName);
        return new MemberTarget(targetOwner, fieldName, false, fieldLocations);
    }

    MemberTarget resolveMethodTarget(
            String ownerType,
            String methodName,
            int argCount,
            List<String> argTypes,
            WorkspaceTypeIndex.Member member) {
        var targetOwner = indexedOwner(ownerType, member);
        if (member != null && member.backingFieldName != null && !member.backingFieldName.isBlank()) {
            var linked =
                    resolveLinkedField(linkedFieldOwner(targetOwner, member), methodName, member.backingFieldName);
            if (!linked.locations().isEmpty()) {
                return linked;
            }
        }

        var direct = findMethodLocations(targetOwner, methodName, argCount, argTypes);
        if (!direct.isEmpty()) {
            return new MemberTarget(targetOwner, methodName, true, direct);
        }

        if (member == null || member.backingFieldName == null || member.backingFieldName.isBlank()) {
            var inferred = LombokAnnotations.backingFieldNameForAccessor(methodName, argCount);
            if (inferred.isPresent()) {
                var attached = resolveAttachedSourceField(targetOwner, inferred.get());
                if (!attached.locations().isEmpty()) {
                    return attached;
                }
                var linked = resolveLinkedField(targetOwner, methodName, inferred.get());
                if (!linked.locations().isEmpty()) {
                    return linked;
                }
            }
        }
        return new MemberTarget(targetOwner, methodName, true, List.of());
    }

    private List<Location> preferAttachedSource(String ownerType, String fieldName, List<Location> locations) {
        if (locations.isEmpty()) {
            return locations;
        }
        var first = locations.getFirst();
        if (first.uri == null
                || !"file".equals(first.uri.getScheme())
                || !first.uri.getPath().contains("jls-binary-decompiled")) {
            return locations;
        }
        var attached = findFieldLocationsInDirectExternalSource(ownerType, fieldName);
        return attached.isEmpty() ? locations : attached;
    }

    List<Location> findConstructorLocations(String ownerType, int argCount) {
        var source = openTypeSource(ownerType);
        if (source.isEmpty()) {
            return List.of();
        }
        var parse = source.get().task;
        var classPath = source.get().classPath;
        var classTree = (ClassTree) classPath.getLeaf();
        var constructorName = classTree.getSimpleName().toString();
        var results = new ArrayList<Location>();
        for (var member : classTree.getMembers()) {
            if (!(member instanceof MethodTree method)) continue;
            if (method.getReturnType() != null) continue;
            if (argCount >= 0 && method.getParameters().size() != argCount) continue;
            var path = new TreePath(classPath, method);
            var location = FindHelper.location(parse, path, constructorName);
            if (location != null) {
                results.add(location);
            }
        }
        return results;
    }

    List<Location> findTypeLocation(String qualifiedType, String labelName) {
        var source = openTypeSource(qualifiedType);
        if (source.isPresent()) {
            var locations = findTypeLocation(source.get(), labelName);
            if (!locations.isEmpty()) {
                return locations;
            }
        }
        var decompiled = openDecompiledTypeSource(qualifiedType);
        if (decompiled.isPresent()) {
            return findTypeLocation(decompiled.get(), labelName);
        }
        return List.of();
    }

    private List<Location> findFieldLocationsInDirectExternalSource(String ownerType, String fieldName) {
        if (completionIndex.isWorkspaceOwnedType(ownerType, compiler)) {
            return List.of();
        }
        var externalSource = typeLookup.findExternalSource(ownerType, "openTypeSource");
        if (externalSource.isEmpty()) {
            return List.of();
        }
        return findFieldLocationsInFile(externalSource.get(), fieldName);
    }

    private MemberTarget resolveAttachedSourceField(String ownerType, String fieldName) {
        var attached = findAttachedSourceFieldLocations(ownerType, fieldName);
        if (attached.isEmpty()) {
            return new MemberTarget(ownerType, fieldName, false, List.of());
        }
        return new MemberTarget(ownerType, fieldName, false, attached);
    }

    private MemberTarget resolveLinkedField(String ownerType, String accessorName, String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return new MemberTarget(ownerType, accessorName, false, List.of());
        }
        var locations = findFieldLocations(ownerType, fieldName);
        if (!locations.isEmpty()) {
            return new MemberTarget(ownerType, fieldName, false, locations);
        }
        return new MemberTarget(ownerType, accessorName, false, List.of());
    }

    private List<Location> findFieldLocationsInFile(JavaFileObject sourceFile, String fieldName) {
        if (sourceFile == null || fieldName == null || fieldName.isBlank()) {
            return List.of();
        }
        try {
            var contents = sourceFile.getCharContent(true).toString();
            var matcher = Pattern.compile("\\b" + Pattern.quote(fieldName) + "\\b").matcher(contents);
            if (!matcher.find()) {
                return List.of();
            }
            var range = org.javacs.FileStore.range(contents, matcher.start(), matcher.end());
            var uri = FindHelper.normalizeLocationUri(sourceFile.toUri());
            return List.of(new Location(uri, range));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Location> findMethodLocations(
            TypeSource source, String methodName, int argCount, List<String> argTypes) {
        var parse = source.task;
        var classPath = source.classPath;
        var classTree = (ClassTree) classPath.getLeaf();
        var methods = new ArrayList<MethodTree>();
        for (var member : classTree.getMembers()) {
            if (!(member instanceof MethodTree method)) continue;
            if (!method.getName().contentEquals(methodName)) continue;
            if (argCount >= 0 && method.getParameters().size() != argCount) continue;
            methods.add(method);
        }
        if (methods.isEmpty()) {
            return List.of();
        }

        var exact = new ArrayList<MethodTree>();
        if (argCount >= 0 && NavigationSymbolSupport.hasResolvedTypes(argTypes)) {
            for (var method : methods) {
                if (matchesArgumentTypes(parse, classPath, method, argTypes)) {
                    exact.add(method);
                }
            }
        }

        var selected = !exact.isEmpty() ? exact : methods;
        var dedupe = new LinkedHashMap<String, Location>();
        for (var method : selected) {
            var path = new TreePath(classPath, method);
            var location = FindHelper.location(parse, path, methodName);
            if (location == null) continue;
            var key = location.uri + ":" + location.range.start.line + ":" + location.range.start.character;
            dedupe.putIfAbsent(key, location);
        }
        return new ArrayList<>(dedupe.values());
    }

    private boolean matchesArgumentTypes(ParseTask parse, TreePath classPath, MethodTree method, List<String> argTypes) {
        if (method.getParameters().size() != argTypes.size()) {
            return false;
        }
        if (!NavigationSymbolSupport.hasResolvedTypes(argTypes)) {
            return false;
        }
        var parameterTypes =
                NavigationSymbolSupport.declaredParameterTypes(
                        parse, new TreePath(classPath, method), method, completionIndex, compiler);
        if (parameterTypes.size() != argTypes.size()) {
            return false;
        }
        for (int i = 0; i < argTypes.size(); i++) {
            if (!parameterTypes.get(i).equals(argTypes.get(i))) {
                return false;
            }
        }
        return true;
    }

    private List<Location> findFieldLocations(TypeSource source, String fieldName) {
        var parse = source.task;
        var classPath = source.classPath;
        var classTree = (ClassTree) classPath.getLeaf();
        if (classTree.getKind() == Tree.Kind.RECORD) {
            var recordComponentLocation = findRecordComponentLocation(parse, classPath, fieldName);
            if (recordComponentLocation != null) {
                return List.of(recordComponentLocation);
            }
        }
        for (var member : classTree.getMembers()) {
            if (!(member instanceof VariableTree variable)) continue;
            if (!variable.getName().contentEquals(fieldName)) continue;
            var path = new TreePath(classPath, variable);
            var location = FindHelper.location(parse, path, fieldName);
            if (location != null) {
                return List.of(location);
            }
        }
        return List.of();
    }

    private Location findRecordComponentLocation(ParseTask parse, TreePath classPath, String fieldName) {
        var positions = Trees.instance(parse.task()).getSourcePositions();
        var root = classPath.getCompilationUnit();
        var start = (int) positions.getStartPosition(root, classPath.getLeaf());
        var end = (int) positions.getEndPosition(root, classPath.getLeaf());
        if (start < 0 || end < start) {
            return null;
        }
        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        var bodyStart = -1;
        for (int i = start; i <= end && i < contents.length(); i++) {
            if (contents.charAt(i) == '{') {
                bodyStart = i;
                break;
            }
        }
        if (bodyStart < 0) {
            return null;
        }
        var componentStart = FindHelper.findNameIn(root, fieldName, start, bodyStart);
        if (componentStart < 0) {
            return null;
        }
        var range = org.javacs.FileStore.range(contents.toString(), componentStart, componentStart + fieldName.length());
        return new Location(root.getSourceFile().toUri(), range);
    }

    private List<Location> findTypeLocation(TypeSource source, String labelName) {
        var path = source.classPath;
        var location = FindHelper.location(source.task, path, labelName);
        if (location == null) {
            location = FindHelper.location(source.task, path);
        }
        if (location == null) {
            return List.of();
        }
        return List.of(location);
    }

    private Optional<TypeSource> openTypeSource(String qualifiedType) {
        var type = completionIndex.workspace().typeInfo(qualifiedType).orElse(null);
        if (type != null && type.sourcePath != null) {
            var parse = compiler.parse(type.sourcePath);
            var classPath = findClassPathOrTopLevel(parse, qualifiedType);
            if (classPath.isPresent()) {
                return Optional.of(new TypeSource(parse, classPath.get()));
            }
        }

        var anywhere = typeLookup.findExternalSource(qualifiedType, "openTypeSource");
        if (anywhere.isPresent()) {
            attachedExternalSources.put(qualifiedType, anywhere.get());
            var parse = compiler.parse(anywhere.get());
            var classPath = findClassPathOrTopLevel(parse, qualifiedType);
            if (classPath.isPresent()) {
                return Optional.of(new TypeSource(parse, classPath.get()));
            }
        }

        for (var i = qualifiedType.lastIndexOf('.'); i > 0; i = qualifiedType.lastIndexOf('.', i - 1)) {
            var outer = qualifiedType.substring(0, i);
            var outerSource = typeLookup.findExternalSource(outer, "openTypeSourceOuter");
            if (outerSource.isEmpty()) {
                continue;
            }
            attachedExternalSources.put(outer, outerSource.get());
            var parse = compiler.parse(outerSource.get());
            var classPath = findClassPathOrTopLevel(parse, qualifiedType);
            if (classPath.isPresent()) {
                return Optional.of(new TypeSource(parse, classPath.get()));
            }
        }
        return Optional.empty();
    }

    private Optional<TypeSource> openDecompiledTypeSource(String qualifiedType) {
        var decompiledSource = completionIndex.externalDecompiledSourcePath(qualifiedType);
        if (decompiledSource.isEmpty()) {
            return Optional.empty();
        }
        var parse = compiler.parse(decompiledSource.get());
        var classPath = findClassPathOrTopLevel(parse, qualifiedType);
        return classPath.map(trees -> new TypeSource(parse, trees));
    }

    private Optional<TreePath> findClassPath(ParseTask parse, String qualifiedType) {
        try {
            var classTree = FindHelper.findType(parse, qualifiedType);
            var path = Trees.instance(parse.task()).getPath(parse.root(), classTree);
            return Optional.ofNullable(path);
        } catch (RuntimeException notFound) {
            return Optional.empty();
        }
    }

    private Optional<TreePath> findClassPathOrTopLevel(ParseTask parse, String qualifiedType) {
        var direct = findClassPath(parse, qualifiedType);
        if (direct.isPresent()) {
            return direct;
        }
        for (var declaration : parse.root().getTypeDecls()) {
            if (!(declaration instanceof ClassTree classTree)) {
                continue;
            }
            var path = Trees.instance(parse.task()).getPath(parse.root(), classTree);
            if (path != null) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    private String indexedOwner(String ownerType, WorkspaceTypeIndex.Member member) {
        if (member != null && member.ownerType != null && !member.ownerType.isBlank()) {
            return member.ownerType;
        }
        return ownerType;
    }

    private String linkedFieldOwner(String ownerType, WorkspaceTypeIndex.Member member) {
        if (member != null && member.logicalKey != null && !member.logicalKey.isBlank()) {
            var split = member.logicalKey.indexOf('#');
            if (split > 0) {
                return member.logicalKey.substring(0, split);
            }
        }
        return ownerType;
    }

    private record TypeSource(ParseTask task, TreePath classPath) {}
}
