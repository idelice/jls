package org.javacs.completion;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TreeVisitor;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import org.javacs.CompileTask;
import org.javacs.LombokAnnotations;
import org.javacs.lsp.CompletionItemKind;

public class TypeMemberIndex {
    public static final TypeMemberIndex EMPTY =
            new TypeMemberIndex(Map.of(), Map.of());
    private static final Set<String> PRIMITIVE_TYPE_NAMES =
            Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char", "void");
    private static final Set<String> LOMBOK_ACCESSOR_ANNOTATIONS =
            Set.of("Data", "Getter", "Setter", "Value");
    public static class Member {
        public final String ownerType;
        public final String name;
        public final int kind;
        public final boolean isStatic;
        public final boolean isPrivate;
        public final int priority;
        public final String detail;
        public final String returnType;
        public final String[] parameterNames;
        public final String[] erasedParameterTypes;

        Member(
                String ownerType,
                String name,
                int kind,
                boolean isStatic,
                boolean isPrivate,
                int priority,
                String detail,
                String returnType,
                String[] parameterNames,
                String[] erasedParameterTypes) {
            this.ownerType = ownerType;
            this.name = name;
            this.kind = kind;
            this.isStatic = isStatic;
            this.isPrivate = isPrivate;
            this.priority = priority;
            this.detail = detail;
            this.returnType = returnType;
            this.parameterNames = parameterNames;
            this.erasedParameterTypes = erasedParameterTypes;
        }
    }

    public static class TypeInfo {
        public final String qualifiedName;
        public final String simpleName;
        public final List<Member> members;
        public final boolean fromCompiledRoot;
        public final Path sourcePath;

        TypeInfo(
                String qualifiedName,
                String simpleName,
                List<Member> members,
                boolean fromCompiledRoot,
                Path sourcePath) {
            this.qualifiedName = qualifiedName;
            this.simpleName = simpleName;
            this.members = Collections.unmodifiableList(new ArrayList<>(members));
            this.fromCompiledRoot = fromCompiledRoot;
            this.sourcePath = sourcePath;
        }
    }

    private final Map<String, TypeInfo> typesByQualifiedName;
    private final Map<Path, Set<String>> workspaceTypesByFile;
    private static final Logger LOG = Logger.getLogger("main");

    private TypeMemberIndex(Map<String, TypeInfo> typesByQualifiedName, Map<Path, Set<String>> workspaceTypesByFile) {
        var verified = new Object2ObjectLinkedOpenHashMap<String, TypeInfo>();
        for (var entry : typesByQualifiedName.entrySet()) {
            var key = entry.getKey();
            var valid = key != null && (key.contains(".") || isPrimitiveTypeName(key));
            assert valid : "TypeMemberIndex key must be fully qualified or primitive: " + key;
            if (!valid) {
                throw new IllegalStateException("TypeMemberIndex key must be fully qualified or primitive: " + key);
            }
            verified.put(key, entry.getValue());
        }
        this.typesByQualifiedName = Collections.unmodifiableMap(verified);
        var verifiedWorkspaceTypes = new Object2ObjectLinkedOpenHashMap<Path, Set<String>>();
        for (var entry : workspaceTypesByFile.entrySet()) {
            verifiedWorkspaceTypes.put(
                    entry.getKey(),
                    Collections.unmodifiableSet(new ObjectLinkedOpenHashSet<>(entry.getValue())));
        }
        this.workspaceTypesByFile = Collections.unmodifiableMap(verifiedWorkspaceTypes);
    }

    public Map<String, TypeInfo> types() {
        return typesByQualifiedName;
    }

    public int size() {
        return typesByQualifiedName.size();
    }

    public TypeMemberIndex replaceWorkspaceDeclarations(TypeMemberIndex updates, Set<Path> replacedFiles) {
        if ((updates == null || updates.workspaceTypesByFile.isEmpty()) && (replacedFiles == null || replacedFiles.isEmpty())) {
            return this;
        }
        var nextTypes = new Object2ObjectLinkedOpenHashMap<String, TypeInfo>(typesByQualifiedName);
        var nextWorkspaceTypes = new Object2ObjectLinkedOpenHashMap<Path, Set<String>>();
        for (var entry : workspaceTypesByFile.entrySet()) {
            nextWorkspaceTypes.put(entry.getKey(), new ObjectLinkedOpenHashSet<>(entry.getValue()));
        }

        var filesToReplace = new ObjectLinkedOpenHashSet<Path>();
        if (replacedFiles != null) {
            filesToReplace.addAll(replacedFiles);
        }
        if (updates != null) {
            filesToReplace.addAll(updates.workspaceTypesByFile.keySet());
        }

        for (var file : filesToReplace) {
            var previousTypes = nextWorkspaceTypes.remove(file);
            if (previousTypes == null) {
                continue;
            }
            for (var qualifiedName : previousTypes) {
                var existing = nextTypes.get(qualifiedName);
                if (existing != null && file.equals(existing.sourcePath)) {
                    nextTypes.remove(qualifiedName);
                }
            }
        }

        if (updates != null) {
            for (var entry : updates.workspaceTypesByFile.entrySet()) {
                var file = entry.getKey();
                var declaredTypes = new ObjectLinkedOpenHashSet<String>(entry.getValue());
                nextWorkspaceTypes.put(file, declaredTypes);
                for (var qualifiedName : declaredTypes) {
                    var typeInfo = updates.typesByQualifiedName.get(qualifiedName);
                    if (typeInfo != null) {
                        nextTypes.put(qualifiedName, typeInfo);
                    }
                }
            }
        }

        return new TypeMemberIndex(nextTypes, nextWorkspaceTypes);
    }

    public List<Member> members(String qualifiedName, boolean staticContext) {
        var type = typesByQualifiedName.get(qualifiedName);
        if (type == null) {
            return List.of();
        }
        var list = new ArrayList<Member>();
        for (var member : type.members) {
            if (staticContext != member.isStatic) {
                continue;
            }
            list.add(member);
        }
        return list;
    }

    public Optional<Member> member(String qualifiedName, String name, boolean staticContext) {
        var type = typesByQualifiedName.get(qualifiedName);
        if (type == null) {
            return Optional.empty();
        }
        for (var member : type.members) {
            if (staticContext != member.isStatic) {
                continue;
            }
            if (!Objects.equals(name, member.name)) {
                continue;
            }
            return Optional.of(member);
        }
        return Optional.empty();
    }

    public Optional<String> resolveTypeName(String typeName, CompilationUnitTree root) {
        if (typeName == null || typeName.isBlank()) {
            return Optional.empty();
        }
        var raw = normalizeTypeName(typeName);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        if (isPrimitiveTypeName(raw)) {
            return Optional.of(raw);
        }
        if (typesByQualifiedName.containsKey(raw)) {
            return Optional.of(raw);
        }
        if (raw.contains(".")) {
            var firstSegmentEnd = raw.indexOf('.');
            if (firstSegmentEnd <= 0) {
                return Optional.empty();
            }
            var firstSegment = raw.substring(0, firstSegmentEnd);
            var suffix = raw.substring(firstSegmentEnd);
            var resolvedPrefix = resolveSimpleName(firstSegment, root);
            if (resolvedPrefix.isEmpty()) {
                return Optional.empty();
            }
            var resolved = resolvedPrefix.get() + suffix;
            if (typesByQualifiedName.containsKey(resolved)) {
                return Optional.of(resolved);
            }
            return Optional.empty();
        }
        return resolveSimpleName(raw, root);
    }

    private Optional<String> resolveSimpleName(String simpleName, CompilationUnitTree root) {
        var candidates = new ObjectLinkedOpenHashSet<String>();
        var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!packageName.isEmpty()) {
            var samePackage = packageName + "." + simpleName;
            if (typesByQualifiedName.containsKey(samePackage)) {
                candidates.add(samePackage);
            }
        }

        for (var importTree : root.getImports()) {
            if (importTree.isStatic()) continue;
            var imported = importTree.getQualifiedIdentifier().toString();
            if (imported.endsWith("." + simpleName) && typesByQualifiedName.containsKey(imported)) {
                candidates.add(imported);
            }
            if (imported.endsWith(".*")) {
                var prefix = imported.substring(0, imported.length() - 1);
                var candidate = prefix + simpleName;
                if (typesByQualifiedName.containsKey(candidate)) {
                    candidates.add(candidate);
                }
            }
        }

        var javaLang = "java.lang." + simpleName;
        if (typesByQualifiedName.containsKey(javaLang)) {
            candidates.add(javaLang);
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.iterator().next());
        }
        return Optional.empty();
    }

    private static String normalizeTypeName(String typeName) {
        var raw = typeName.trim();
        while (raw.endsWith("[]")) {
            raw = raw.substring(0, raw.length() - 2);
        }
        var genericStart = raw.indexOf('<');
        if (genericStart >= 0) {
            raw = raw.substring(0, genericStart);
        }
        if (raw.startsWith("? extends ")) {
            raw = raw.substring("? extends ".length()).trim();
        } else if (raw.startsWith("? super ")) {
            raw = raw.substring("? super ".length()).trim();
        } else if ("?".equals(raw)) {
            raw = "";
        }
        return raw;
    }

    public static boolean isPrimitiveTypeName(String typeName) {
        return PRIMITIVE_TYPE_NAMES.contains(typeName);
    }

    public static TypeMemberIndex from(CompileTask task) {
        return from(task, true);
    }

    public static TypeMemberIndex workspaceDeclarations(CompileTask task) {
        return from(task, false);
    }

    private static TypeMemberIndex from(CompileTask task, boolean includeReferencedTypes) {
        var trees = Trees.instance(task.task);
        var elements = task.task.getElements();
        var types = task.task.getTypes();

        var rootDeclaredTypeSources = new Object2ObjectOpenHashMap<String, Path>();
        var rootDeclaredTypeTrees = new Object2ObjectOpenHashMap<String, ClassTree>();
        var collectedTypes = new Object2ObjectLinkedOpenHashMap<String, TypeElement>();
        var seenMirrors = new ObjectOpenHashSet<String>();
        var skippedInvalidTypeKeys = new ObjectLinkedOpenHashSet<String>();
        for (var root : task.roots) {
            Path sourcePath = null;
            var sourceUri = root.getSourceFile().toUri();
            if (sourceUri != null && "file".equals(sourceUri.getScheme())) {
                sourcePath = Paths.get(sourceUri);
            }
            var rootPath = sourcePath;
            new TreePathScanner<Void, Void>() {
                @Override
                    public Void visitClass(ClassTree tree, Void p) {
                        var element = trees.getElement(getCurrentPath());
                        if (element instanceof TypeElement typeElement) {
                            var qualifiedName = typeElement.getQualifiedName().toString();
                            if (isValidIndexKey(qualifiedName)) {
                            collectedTypes.putIfAbsent(qualifiedName, typeElement);
                            if (rootPath != null) {
                                rootDeclaredTypeSources.put(qualifiedName, rootPath);
                            }
                            rootDeclaredTypeTrees.putIfAbsent(qualifiedName, tree);
                            } else if (qualifiedName != null && !qualifiedName.isBlank()) {
                                skippedInvalidTypeKeys.add(qualifiedName);
                            }
                            if (LombokAnnotations.hasLoggingOnlyLombokAnnotation(tree.getModifiers())) {
                                collectNamedType(elements, "org.slf4j.Logger", collectedTypes);
                            }
                        }
                        return super.visitClass(tree, p);
                    }
            }.scan(root, null);

            if (includeReferencedTypes) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitVariable(VariableTree tree, Void p) {
                        collectTypeMirror(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitVariable(tree, p);
                    }

                    @Override
                    public Void visitMethod(MethodTree tree, Void p) {
                        collectTypeMirror(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitMethod(tree, p);
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree tree, Void p) {
                        collectTypeMirror(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitMethodInvocation(tree, p);
                    }

                    @Override
                    public Void visitIdentifier(IdentifierTree tree, Void p) {
                        collectTypeMirror(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitIdentifier(tree, p);
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree tree, Void p) {
                        collectTypeMirror(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitMemberSelect(tree, p);
                    }

                    @Override
                    public Void visitNewClass(NewClassTree tree, Void p) {
                        collectTypeMirror(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitNewClass(tree, p);
                    }
                }.scan(root, null);
            }
        }

        var typeEntries = new Object2ObjectLinkedOpenHashMap<String, TypeInfo>();
        for (var collected : collectedTypes.values()) {
            var qualifiedName = collected.getQualifiedName().toString();
            if (!isValidIndexKey(qualifiedName)) {
                if (qualifiedName != null && !qualifiedName.isBlank()) {
                    skippedInvalidTypeKeys.add(qualifiedName);
                }
                continue;
            }
            var type = elements.getTypeElement(qualifiedName);
            if (type == null) {
                type = collected;
            } else if (type != collected) {
                LOG.fine(String.format("[completion] canonicalized type symbol %s for index extraction", qualifiedName));
            }

            var members = new ArrayList<Member>();
            var seen = new Object2ObjectOpenHashMap<String, Member>();
            for (var member : elements.getAllMembers(type)) {
                if (member.getKind() == ElementKind.CONSTRUCTOR) continue;
                var kind = memberKind(member);
                if (kind == null) continue;

                var ownerElement = member.getEnclosingElement();
                if (!(ownerElement instanceof TypeElement)) continue;
                var ownerType = (TypeElement) ownerElement;
                var ownerName = ownerType.getQualifiedName().toString();
                var declaredInOwner = qualifiedName.equals(ownerName);
                if (!declaredInOwner && member.getModifiers().contains(Modifier.PRIVATE)) continue;
                var declaredInObject = "java.lang.Object".equals(ownerName);
                var priority = declaredInOwner ? 0 : declaredInObject ? 2 : 1;
                var isStatic = member.getModifiers().contains(Modifier.STATIC);
                var isPrivate = member.getModifiers().contains(Modifier.PRIVATE);

                String detail;
                String returnType = null;
                String[] parameterNames = null;
                String[] erasedParameterTypes = null;
                if (member instanceof ExecutableElement executable) {
                    detail = executable.getReturnType() + " " + executable;
                    returnType = typeName(executable.getReturnType());
                    parameterNames = new String[executable.getParameters().size()];
                    erasedParameterTypes = new String[executable.getParameters().size()];
                    for (int i = 0; i < executable.getParameters().size(); i++) {
                        var param = executable.getParameters().get(i);
                        parameterNames[i] = param.getSimpleName().toString();
                        erasedParameterTypes[i] = types.erasure(param.asType()).toString();
                    }
                } else {
                    detail = member.asType() + " " + member.getSimpleName();
                    returnType = typeName(member.asType());
                }

                var next =
                        new Member(
                                ownerName,
                                member.getSimpleName().toString(),
                                kind,
                                isStatic,
                                isPrivate,
                                priority,
                                detail,
                                returnType,
                                parameterNames,
                                erasedParameterTypes);
                var key = next.kind + ":" + next.name + ":" + String.join(",", next.erasedParameterTypes == null ? new String[0] : next.erasedParameterTypes);
                var existing = seen.get(key);
                if (existing == null || next.priority < existing.priority) {
                    seen.put(key, next);
                }
            }
            var declaredTree = rootDeclaredTypeTrees.get(qualifiedName);
            addSyntheticLombokAccessors(qualifiedName, declaredTree, seen);
            addSyntheticSlf4jLoggerField(qualifiedName, declaredTree, seen);

            members.addAll(seen.values());
            members.sort(
                    Comparator.comparingInt((Member m) -> m.priority)
                            .thenComparing(m -> m.name, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(m -> m.detail));

            var sourcePath = rootDeclaredTypeSources.get(qualifiedName);
            var fromCompiledRoot = sourcePath != null;
            var typeEntry =
                    new TypeInfo(
                            qualifiedName,
                            type.getSimpleName().toString(),
                            Collections.unmodifiableList(members),
                            fromCompiledRoot,
                            sourcePath);
            typeEntries.put(qualifiedName, typeEntry);
        }

        var workspaceTypes = new Object2ObjectLinkedOpenHashMap<Path, Set<String>>();
        for (var entry : rootDeclaredTypeSources.entrySet()) {
            workspaceTypes.computeIfAbsent(entry.getValue(), __ -> new ObjectLinkedOpenHashSet<>()).add(entry.getKey());
        }

        if (!skippedInvalidTypeKeys.isEmpty()) {
            LOG.fine(
                    String.format(
                            "[completion] skipped non-fqn types while building index: %s",
                            skippedInvalidTypeKeys));
        }
        return new TypeMemberIndex(Collections.unmodifiableMap(typeEntries), Collections.unmodifiableMap(workspaceTypes));
    }

    private static Integer memberKind(Element member) {
        if (member.getKind() == ElementKind.METHOD) {
            return CompletionItemKind.Method;
        }
        if (member.getKind() == ElementKind.FIELD || member.getKind() == ElementKind.ENUM_CONSTANT) {
            return CompletionItemKind.Field;
        }
        if (member.getKind() == ElementKind.CLASS || member.getKind() == ElementKind.RECORD) {
            return CompletionItemKind.Class;
        }
        if (member.getKind() == ElementKind.INTERFACE || member.getKind() == ElementKind.ANNOTATION_TYPE) {
            return CompletionItemKind.Interface;
        }
        if (member.getKind() == ElementKind.ENUM) {
            return CompletionItemKind.Enum;
        }
        return null;
    }

    private static String typeName(TypeMirror mirror) {
        if (mirror == null) return null;
        if (mirror instanceof DeclaredType declaredType) {
            var element = declaredType.asElement();
            if (element instanceof TypeElement typeElement) {
                var qualifiedName = typeElement.getQualifiedName().toString();
                if (!qualifiedName.isBlank()) {
                    return qualifiedName;
                }
            }
            return declaredType.toString();
        }
        if (mirror instanceof TypeVariable typeVariable) {
            return typeName(typeVariable.getUpperBound());
        }
        if (mirror instanceof ArrayType arrayType) {
            var component = typeName(arrayType.getComponentType());
            if (component == null) {
                return null;
            }
            return component + "[]";
        }
        return mirror.toString();
    }

    private static void collectTypeMirror(
            TypeMirror mirror, Map<String, TypeElement> collectedTypes, Set<String> seenMirrors) {
        if (mirror == null) {
            return;
        }
        var key = mirror.getKind() + ":" + mirror;
        if (!seenMirrors.add(key)) {
            return;
        }
        if (mirror instanceof DeclaredType declaredType) {
            var element = declaredType.asElement();
            if (element instanceof TypeElement typeElement) {
                var qualifiedName = typeElement.getQualifiedName().toString();
                if (isValidIndexKey(qualifiedName)) {
                    collectedTypes.putIfAbsent(qualifiedName, typeElement);
                }
            }
            for (var argument : declaredType.getTypeArguments()) {
                collectTypeMirror(argument, collectedTypes, seenMirrors);
            }
            return;
        }
        if (mirror instanceof TypeVariable typeVariable) {
            collectTypeMirror(typeVariable.getUpperBound(), collectedTypes, seenMirrors);
            return;
        }
        if (mirror instanceof ArrayType arrayType) {
            collectTypeMirror(arrayType.getComponentType(), collectedTypes, seenMirrors);
            return;
        }
        if (mirror instanceof WildcardType wildcardType) {
            collectTypeMirror(wildcardType.getExtendsBound(), collectedTypes, seenMirrors);
            collectTypeMirror(wildcardType.getSuperBound(), collectedTypes, seenMirrors);
            return;
        }
        if (mirror instanceof IntersectionType intersectionType) {
            for (var bound : intersectionType.getBounds()) {
                collectTypeMirror(bound, collectedTypes, seenMirrors);
            }
            return;
        }
        if (mirror instanceof UnionType unionType) {
            for (var alternative : unionType.getAlternatives()) {
                collectTypeMirror(alternative, collectedTypes, seenMirrors);
            }
        }
    }

    private static void collectNamedType(
            javax.lang.model.util.Elements elements,
            String qualifiedName,
            Map<String, TypeElement> collectedTypes) {
        if (!isValidIndexKey(qualifiedName) || collectedTypes.containsKey(qualifiedName)) {
            return;
        }
        var type = elements.getTypeElement(qualifiedName);
        if (type != null) {
            collectedTypes.put(qualifiedName, type);
        }
    }

    private static boolean isValidIndexKey(String key) {
        return key != null && !key.isBlank() && (key.contains(".") || isPrimitiveTypeName(key));
    }

    private static void addSyntheticLombokAccessors(
            String ownerQualifiedName, ClassTree declaration, Map<String, Member> seen) {
        if (declaration == null || !hasAnyLombokAccessorAnnotation(declaration.getModifiers())) {
            return;
        }
        var classGetter = hasLombokAnnotation(declaration.getModifiers(), "Data", "Getter", "Value");
        var classSetter = hasLombokAnnotation(declaration.getModifiers(), "Data", "Setter");
        for (var member : declaration.getMembers()) {
            if (!(member instanceof VariableTree variable)) {
                continue;
            }
            if (variable.getName() == null) {
                continue;
            }
            if (variable.getModifiers().getFlags().contains(Modifier.STATIC)) {
                continue;
            }
            var fieldName = variable.getName().toString();
            if (fieldName.isBlank()) {
                continue;
            }
            var fieldType = variable.getType() == null ? "java.lang.Object" : variable.getType().toString();
            var getterEnabled = classGetter || hasLombokAnnotation(variable.getModifiers(), "Getter");
            var setterEnabled = classSetter || hasLombokAnnotation(variable.getModifiers(), "Setter");
            var suffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            var booleanField = "boolean".equals(fieldType) || "java.lang.Boolean".equals(fieldType);
            if (getterEnabled) {
                var getterName = (booleanField ? "is" : "get") + suffix;
                putSyntheticMethod(
                        seen,
                        new Member(
                                ownerQualifiedName,
                                getterName,
                                CompletionItemKind.Method,
                                false,
                                false,
                                0,
                                fieldType + " " + getterName + "()",
                                fieldType,
                                new String[0],
                                new String[0]));
            }
            if (setterEnabled) {
                var setterName = "set" + suffix;
                putSyntheticMethod(
                        seen,
                        new Member(
                                ownerQualifiedName,
                                setterName,
                                CompletionItemKind.Method,
                                false,
                                false,
                                0,
                                "void " + setterName + "(" + fieldType + " " + fieldName + ")",
                                "void",
                                new String[] {fieldName},
                                new String[] {normalizeTypeName(fieldType)}));
            }
        }
    }

    private static void addSyntheticSlf4jLoggerField(
            String ownerQualifiedName, ClassTree declaration, Map<String, Member> seen) {
        if (declaration == null || !LombokAnnotations.hasLoggingOnlyLombokAnnotation(declaration.getModifiers())) {
            return;
        }
        var next =
                new Member(
                        ownerQualifiedName,
                        "log",
                        CompletionItemKind.Field,
                        true,
                        true,
                        0,
                        "org.slf4j.Logger log",
                        "org.slf4j.Logger",
                        null,
                        null);
        seen.putIfAbsent(next.kind + ":" + next.name + ":", next);
    }

    private static void putSyntheticMethod(Map<String, Member> seen, Member next) {
        var key =
                next.kind
                        + ":"
                        + next.name
                        + ":"
                        + String.join(",", next.erasedParameterTypes == null ? new String[0] : next.erasedParameterTypes);
        seen.putIfAbsent(key, next);
    }

    private static boolean hasAnyLombokAccessorAnnotation(ModifiersTree modifiers) {
        for (var annotation : modifiers.getAnnotations()) {
            var annotationType = annotation.getAnnotationType().toString();
            if (!isLombokAnnotationType(annotationType)) {
                continue;
            }
            var simpleName = LombokAnnotations.simpleName(annotationType);
            if (LOMBOK_ACCESSOR_ANNOTATIONS.contains(simpleName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLombokAnnotation(ModifiersTree modifiers, String... simpleNames) {
        var allowed = Set.of(simpleNames);
        for (var annotation : modifiers.getAnnotations()) {
            var annotationType = annotation.getAnnotationType().toString();
            if (!isLombokAnnotationType(annotationType)) {
                continue;
            }
            if (allowed.contains(LombokAnnotations.simpleName(annotationType))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLombokAnnotationType(String annotationType) {
        return LombokAnnotations.isLombokAnnotationType(annotationType);
    }
}
