package org.javacs.provider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import org.javacs.CompileTask;
import org.javacs.FindHelper;
import org.javacs.ElementSignatureHelper;
import org.javacs.ParseTask;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.SourceFileObject;
import org.javacs.completion.FindInvocationAt;
import org.javacs.completion.PruneMethodBodies;
import org.javacs.index.IndexedMember;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.ParameterInformation;
import org.javacs.lsp.SignatureHelp;
import org.javacs.lsp.SignatureInformation;

public class SignatureProvider {
    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;
    private final WorkspaceTypeIndex index;

    public static final SignatureHelp NOT_SUPPORTED = new SignatureHelp(List.of(), -1, -1);

    public SignatureProvider(CompilerProvider compiler, WorkspaceTypeIndex index) {
        this.compiler = compiler;
        this.index = index == null ? WorkspaceTypeIndex.EMPTY : index;
    }

    public SignatureHelp signatureHelp(Path file, int line, int column) {
        try {
            var parse = compiler.parse(file);
            var content = parse.root().getSourceFile().getCharContent(true).toString();
            long cursor = FileStore.offset(content, line, column);
            var buffer = new PruneMethodBodies(parse.task()).scan(parse.root(), cursor);
            buffer.insert(endOfLine(buffer, (int) cursor), ';');
            var pruned = buffer.toString();
            var source = new SourceFileObject(file, pruned, Instant.now());
            try (var task = compiler.compile(List.of(source), true)) {
                var root = task.root(source);
                if (root == null) return NOT_SUPPORTED;
                var invocation = new FindInvocationAt(task.task).scan(root, (Long) cursor);
                if (invocation == null) {
                    return NOT_SUPPORTED;
                }
                var positions = task.trees.getSourcePositions();
                List<? extends ExpressionTree> arguments;
                List<ExecutableElement> methods = List.of();
                List<IndexedMember> workspaceOverloads = List.of();
                long invocationStart;
                if (invocation.getLeaf() instanceof MethodInvocationTree invoke) {
                    arguments = invoke.getArguments();
                    invocationStart = positions.getEndPosition(root, invoke.getMethodSelect()) + 1;
                    var methodName = invoke.getMethodSelect() instanceof IdentifierTree identifier
                            ? identifier.getName().toString() : ((MemberSelectTree) invoke.getMethodSelect()).getIdentifier().toString();
                    for (var candidate : findMethodOwnersWithStaticContext(task, root, invocation, invoke).entrySet()) {
                        boolean staticOnly = candidate.getValue();
                        methods = findAccessibleMethodOverloads(task, invocation, candidate.getKey(), methodName, staticOnly);
                        if (!methods.isEmpty()) break;
                        workspaceOverloads = staticOnly ? index.methodOverloads(candidate.getKey(), methodName, true)
                                : new ArrayList<>(index.methodOverloads(candidate.getKey(), methodName, false));
                        if (!staticOnly) workspaceOverloads.addAll(index.methodOverloads(candidate.getKey(), methodName, true));
                        if (!workspaceOverloads.isEmpty()) break;
                    }
                } else if (invocation.getLeaf() instanceof NewClassTree constructor) {
                    arguments = constructor.getArguments();
                    invocationStart = positions.getEndPosition(root, constructor.getIdentifier()) + 1;
                    var ownerName = constructorTypeName(task, invocation, root, constructor);
                    var owner = ownerName == null ? null : task.elements.getTypeElement(ownerName);
                    var scope = task.trees.getScope(invocation);
                    methods = owner == null || !(owner.asType() instanceof DeclaredType declared) ? List.of()
                            : ElementFilter.constructorsIn(owner.getEnclosedElements()).stream()
                                    .filter(method -> task.trees.isAccessible(scope, method, declared)).toList();
                    if (methods.isEmpty() && ownerName != null) workspaceOverloads = index.constructors(ownerName);
                } else {
                    return NOT_SUPPORTED;
                }
                if (methods.isEmpty() && workspaceOverloads.isEmpty()) {
                    return NOT_SUPPORTED;
                }
                var activeParameter = activeParameterAt(task, root, arguments, pruned, invocationStart, cursor);
                var activeSignature = methods.isEmpty()
                        ? selectWorkspaceOverload(task, invocation, workspaceOverloads, arguments, activeParameter)
                        : selectCompiledOverload(task, invocation, methods, arguments, activeParameter);
                List<SignatureInformation> signatures;
                if (methods.isEmpty()) {
                    signatures = new ArrayList<>();
                    for (var member : workspaceOverloads) {
                        var info = new SignatureInformation();
                        info.label = buildLabelFromIndex(member);
                        info.parameters = buildParametersFromIndex(member);
                        signatures.add(info);
                    }
                } else {
                    signatures = renderCompiledSignatures(task, methods);
                }
                var count = signatures.get(activeSignature).parameters.size();
                activeParameter = Math.min(activeParameter, Math.max(0, count - 1));
                return new SignatureHelp(signatures, activeSignature, activeParameter);
            }
        } catch (IOException | RuntimeException | AssertionError failure) {
            LOG.fine(String.format("[perf] signature_help_skip file=%s reason=%s message=%s",
                    file.getFileName(), failure.getClass().getSimpleName(), failure.getMessage()));
            return NOT_SUPPORTED;
        }
    }

    private List<SignatureInformation> renderCompiledSignatures(
            CompileTask task, List<ExecutableElement> methods) {
        String previousOwner = null;
        byte[] ownerClassBytes = null;
        ParseTask ownerSource = null;
        boolean sourceChecked = false, classBytesChecked = false;
        var signatures = new ArrayList<SignatureInformation>();
        for (var method : methods) {
            var ownerName = ElementSignatureHelper.ownerQualifiedName(method);
            if (!Objects.equals(ownerName, previousOwner)) {
                previousOwner = ownerName;
                ownerClassBytes = null;
                ownerSource = null;
                sourceChecked = false;
                classBytesChecked = false;
            }
            if (ElementSignatureHelper.hasSyntheticNames(method.getParameters()) && !classBytesChecked) {
                ownerClassBytes = compiler.findClassFile(ownerName).orElse(null);
                classBytesChecked = true;
            }
            var names = ElementSignatureHelper.parameterNames(task, method, ownerClassBytes);
            if (Arrays.stream(names).anyMatch(name -> name.matches("arg\\d+"))) {
                if (!sourceChecked) {
                    var found = compiler.findAnywhere(ownerName);
                    if (found.isPresent()) {
                        ownerSource = compiler.parse(found.get());
                    }
                    sourceChecked = true;
                }
                if (ownerSource != null) names = readSourceParameterNames(task, method, ownerSource, names);
            }
            signatures.add(buildCompiledSignature(method, names));
        }
        return signatures;
    }

    private static String[] readSourceParameterNames(
            CompileTask task, ExecutableElement method, ParseTask source, String[] fallback) {
        try {
            var declaration = FindHelper.findMethod(source, ElementSignatureHelper.ownerQualifiedName(method),
                    method.getSimpleName().toString(), FindHelper.erasedParameterTypes(task, method));
            return declaration.getParameters().stream()
                    .map(parameter -> parameter.getName().toString()).toArray(String[]::new);
        } catch (RuntimeException missingDeclaration) {
            return fallback;
        }
    }

    private static SignatureInformation buildCompiledSignature(ExecutableElement method, String[] names) {
        var info = new SignatureInformation();
        info.parameters = new ArrayList<>();
        var name = method.getKind() == ElementKind.CONSTRUCTOR
                ? method.getEnclosingElement().getSimpleName() : method.getSimpleName();
        var label = new StringBuilder(name).append('(');
        for (int i = 0; i < method.getParameters().size(); i++) {
            var parameter = new ParameterInformation();
            parameter.label = ElementSignatureHelper.simpleTypeName(method.getParameters().get(i).asType());
            if (method.isVarArgs() && i == method.getParameters().size() - 1 && parameter.label.endsWith("[]")) {
                parameter.label = parameter.label.substring(0, parameter.label.length() - 2) + "...";
            }
            if (!names[i].matches("arg\\d+")) parameter.label += " " + names[i];
            if (i > 0) label.append(", ");
            label.append(parameter.label);
            info.parameters.add(parameter);
        }
        info.label = label.append(')').toString();
        return info;
    }

    private static int endOfLine(CharSequence contents, int cursor) {
        while (cursor < contents.length()) {
            var c = contents.charAt(cursor);
            if (c == '\r' || c == '\n') break;
            cursor++;
        }
        return cursor;
    }

    private static int activeParameterAt(
            CompileTask task, CompilationUnitTree root, List<? extends ExpressionTree> arguments,
            String content, long openParen, long cursor) {
        var positions = task.trees.getSourcePositions();
        long previousEnd = openParen;
        int previous = 0;
        for (int i = 0; i < arguments.size(); i++) {
            long start = positions.getStartPosition(root, arguments.get(i));
            long end = positions.getEndPosition(root, arguments.get(i));
            if (start < 0 || end < start) continue;
            if (cursor < start) break;
            if (cursor <= end) return i;
            previousEnd = end;
            previous = i;
        }
        var gap = content.substring((int) previousEnd, (int) cursor)
                .replaceAll("(?s)/\\*.*?(?:\\*/|$)|//[^\\r\\n]*", "");
        return previous + (gap.indexOf(',') >= 0 ? 1 : 0);
    }

    private Map<String, Boolean> findMethodOwnersWithStaticContext(
            CompileTask task, CompilationUnitTree root, TreePath invocation, MethodInvocationTree invoke) {
        var owners = new LinkedHashMap<String, Boolean>();
        var select = invoke.getMethodSelect();
        if (select instanceof MemberSelectTree memberSelect) {
            var receiver = new TreePath(invocation, memberSelect.getExpression());
            var ownerName = receiverTypeName(task, receiver);
            if (ownerName != null) owners.put(ownerName, task.trees.getElement(receiver) instanceof TypeElement);
            return owners;
        }
        if (!(select instanceof IdentifierTree identifier)) return owners;
        boolean staticOnly = false;
        for (var enclosing = invocation.getParentPath(); enclosing != null; enclosing = enclosing.getParentPath()) {
            var leaf = enclosing.getLeaf();
            if (leaf instanceof MethodTree method && method.getModifiers().getFlags().contains(Modifier.STATIC)
                    || leaf instanceof VariableTree variable && variable.getModifiers().getFlags().contains(Modifier.STATIC)
                    || leaf instanceof BlockTree block && block.isStatic()) staticOnly = true;
            if (!(leaf instanceof ClassTree classTree)) continue;
            var element = task.trees.getElement(enclosing);
            var ownerName = element instanceof TypeElement owner
                    ? owner.getQualifiedName().toString() : qualifiedClassName(root, classTree);
            if (ownerName != null) owners.putIfAbsent(ownerName, staticOnly);
            if (classTree.getModifiers().getFlags().contains(Modifier.STATIC)) staticOnly = true;
        }
        for (var imported : root.getImports()) {
            if (!imported.isStatic()) continue;
            var name = imported.getQualifiedIdentifier().toString();
            if (name.endsWith(".*") || name.endsWith("." + identifier.getName())) {
                owners.putIfAbsent(name.substring(0, name.lastIndexOf('.')), true);
            }
        }
        return owners;
    }

    private List<ExecutableElement> findAccessibleMethodOverloads(
            CompileTask task, TreePath invocation, String ownerName, String methodName, boolean staticOnly) {
        var owner = task.elements.getTypeElement(ownerName);
        if (owner == null || !(owner.asType() instanceof DeclaredType declared)) return List.of();
        var scope = task.trees.getScope(invocation);
        return ElementFilter.methodsIn(task.elements.getAllMembers(owner)).stream()
                .filter(method -> method.getSimpleName().contentEquals(methodName))
                .filter(method -> task.elements.getOrigin(method) != Elements.Origin.SYNTHETIC)
                .filter(method -> !staticOnly || method.getModifiers().contains(Modifier.STATIC))
                .filter(method -> task.trees.isAccessible(scope, method, declared)).toList();
    }

    private static int selectCompiledOverload(
            CompileTask task, TreePath invocation, List<ExecutableElement> methods,
            List<? extends ExpressionTree> arguments, int activeParameter) {
        var resolved = task.trees.getElement(invocation);
        var exact = methods.indexOf(resolved);
        if (exact >= 0) return exact;
        int required = Math.max(arguments.size(), activeParameter > 0 ? activeParameter + 1 : 0);
        int closest = 0, compatible = -1, compatibleCapacity = Integer.MAX_VALUE;
        int closestCount = methods.getFirst().getParameters().size();
        for (int i = 0; i < methods.size(); i++) {
            var method = methods.get(i);
            int count = method.getParameters().size();
            if ((count >= required && count < closestCount) || (closestCount < required && count > closestCount)) {
                closest = i;
                closestCount = count;
            }
            int capacity = method.isVarArgs() ? Math.max(count, required) : count;
            if (compatible >= 0 && !((capacity >= required && capacity < compatibleCapacity)
                    || (compatibleCapacity < required && capacity > compatibleCapacity))) continue;
            if (acceptsArguments(task, invocation, method, arguments)) {
                compatible = i;
                compatibleCapacity = capacity;
            }
        }
        return compatible >= 0 ? compatible : closest;
    }

    private static int selectWorkspaceOverload(
            CompileTask task, TreePath invocation, List<IndexedMember> overloads,
            List<? extends ExpressionTree> arguments, int activeParameter) {
        var resolved = task.trees.getElement(invocation);
        if (resolved instanceof ExecutableElement method) {
            int selected = overloadIndex(task, method, overloads);
            if (selected >= 0) return selected;
        }

        // An unfinished call may have no applicable javac symbol yet. Prefer a type-compatible
        // overload with room for the arguments; if none fits, keep the closest arity visible.
        int required = Math.max(arguments.size(), activeParameter > 0 ? activeParameter + 1 : 0);
        int closest = 0;
        int closestCount = indexParameterTypes(overloads.getFirst()).length;
        for (int i = 1; i < overloads.size(); i++) {
            int count = indexParameterTypes(overloads.get(i)).length;
            if ((count >= required && count < closestCount)
                    || (closestCount < required && count > closestCount)) {
                closest = i;
                closestCount = count;
            }
        }
        return closest;
    }

    private static int overloadIndex(CompileTask task, ExecutableElement method, List<IndexedMember> overloads) {
        if (!method.getSimpleName().contentEquals(overloads.getFirst().name)) return -1;
        var owner = (TypeElement) method.getEnclosingElement();
        var parameterTypes = FindHelper.erasedParameterTypes(task, method);
        for (int i = 0; i < overloads.size(); i++) {
            var member = overloads.get(i);
            if (owner.getQualifiedName().contentEquals(member.ownerType)
                    && method.getSimpleName().contentEquals(member.name)
                    && Arrays.equals(parameterTypes, member.erasedParameterTypes)) return i;
        }
        return -1;
    }

    private static boolean acceptsArguments(
            CompileTask task, TreePath invocation, ExecutableElement method,
            List<? extends ExpressionTree> arguments) {
        var parameters = method.getParameters();
        if (parameters.isEmpty()) return arguments.isEmpty();
        for (int i = 0; i < arguments.size(); i++) {
            if (i >= parameters.size() && !method.isVarArgs()) break;
            var argument = arguments.get(i);
            if (argument.getKind() == Tree.Kind.ERRONEOUS) continue;
            var type = task.trees.getTypeMirror(new TreePath(invocation, argument));
            if (type == null || type.getKind() == TypeKind.ERROR || type.getKind() == TypeKind.NONE) continue;
            var target = parameters.get(Math.min(i, parameters.size() - 1)).asType();
            if (method.isVarArgs() && i >= parameters.size() - 1
                    && target instanceof ArrayType array
                    && !task.types.isAssignable(type, target)) target = array.getComponentType();
            if (!task.types.isAssignable(type, target)) return false;
        }
        return true;
    }

    /** Attributed type of a receiver expression, as a qualified name, or null if unresolved. */
    private String receiverTypeName(CompileTask task, TreePath receiverPath) {
        var type = task.trees.getTypeMirror(receiverPath);
        if (type instanceof TypeVariable tv) {
            type = tv.getUpperBound();
        }
        if (type == null || type.getKind() == TypeKind.ERROR || !(type instanceof DeclaredType declared)) {
            return null;
        }
        if (declared.asElement() instanceof TypeElement te) {
            return te.getQualifiedName().toString();
        }
        return null;
    }

    private String constructorTypeName(
            CompileTask task, TreePath newClassPath, CompilationUnitTree root, NewClassTree nc) {
        var fromMirror = receiverTypeName(task, new TreePath(newClassPath, nc.getIdentifier()));
        if (fromMirror != null) return fromMirror;
        Tree id = nc.getIdentifier();
        if (id instanceof ParameterizedTypeTree ptt) {
            id = ptt.getType();
        }
        var rawPath = new TreePath(newClassPath, id);
        var element = task.trees.getElement(rawPath);
        if (element instanceof TypeElement te) {
            return te.getQualifiedName().toString();
        }
        return null;
    }

    private static String qualifiedClassName(CompilationUnitTree root, ClassTree classTree) {
        var simpleName = classTree.getSimpleName().toString();
        if (simpleName.isEmpty()) return null;
        var pkg = root.getPackageName();
        if (pkg != null) {
            return pkg + "." + simpleName;
        }
        return simpleName;
    }

    /** Fallback for workspace declarations unavailable to this bounded compilation. */
    private String buildLabelFromIndex(IndexedMember member) {
        var name = member.kind == CompletionItemKind.Constructor
                ? member.ownerType.substring(member.ownerType.lastIndexOf('.') + 1)
                : member.name;
        var types = indexParameterTypes(member);
        var names = member.parameterNames;
        var sb = new StringBuilder(name).append('(');
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(simpleTypeName(types[i]));
            if (names != null && i < names.length && names[i] != null && !names[i].isBlank()) {
                sb.append(' ').append(names[i]);
            }
        }
        return sb.append(')').toString();
    }

    private List<ParameterInformation> buildParametersFromIndex(IndexedMember member) {
        var result = new ArrayList<ParameterInformation>();
        var types = indexParameterTypes(member);
        var names = member.parameterNames;
        for (int i = 0; i < types.length; i++) {
            var info = new ParameterInformation();
            var type = simpleTypeName(types[i]);
            info.label = (names != null && i < names.length && names[i] != null && !names[i].isBlank())
                    ? type + " " + names[i]
                    : type;
            result.add(info);
        }
        return result;
    }

    private static String[] indexParameterTypes(IndexedMember member) {
        if (member.declaredParameterTypes != null && member.declaredParameterTypes.length > 0) {
            return member.declaredParameterTypes;
        }
        return member.erasedParameterTypes == null ? new String[0] : member.erasedParameterTypes;
    }

    private static String simpleTypeName(String type) {
        if (type == null) return "";
        var generic = type.indexOf('<');
        var head = generic < 0 ? type : type.substring(0, generic);
        var dot = head.lastIndexOf('.');
        var simpleHead = dot < 0 ? head : head.substring(dot + 1);
        return generic < 0 ? simpleHead : simpleHead + type.substring(generic);
    }
}
