package org.javacs.provider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVariable;
import org.javacs.CompileTask;
import org.javacs.FindHelper;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.SourceFileObject;
import org.javacs.completion.FindInvocationAt;
import org.javacs.completion.PruneMethodBodies;
import org.javacs.index.IndexedMember;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.ParameterInformation;
import org.javacs.lsp.SignatureHelp;
import org.javacs.lsp.SignatureInformation;

public class SignatureProvider {
    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;
    private final TypeIndexRouter index;

    public static final SignatureHelp NOT_SUPPORTED = new SignatureHelp(List.of(), -1, -1);

    public SignatureProvider(CompilerProvider compiler, TypeIndexRouter index) {
        this.compiler = compiler;
        this.index = index == null ? TypeIndexRouter.EMPTY : index;
    }

    public SignatureHelp signatureHelp(Path file, int line, int column) {
        var parse = compiler.parse(file);
        String content;
        try {
            content = parse.root().getSourceFile().getCharContent(true).toString();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        long cursor = FileStore.offset(content, line, column);
        var buffer = new PruneMethodBodies(parse.task()).scan(parse.root(), cursor);
        var endOfLine = endOfLine(buffer, (int) cursor);
        buffer.insert(endOfLine, ';');
        var pruned = buffer.toString();
        var source = new SourceFileObject(file, pruned, Instant.now());
        try (var task = compiler.compile(List.of(source), true)) {
            var root = task.root(source);
            if (root == null) return NOT_SUPPORTED;
            var attrPath = new FindInvocationAt(task.task).scan(root, (Long) cursor);
            if (attrPath == null) return NOT_SUPPORTED;

            var sourcePos = Trees.instance(task.task).getSourcePositions();
            long invocationStart;
            if (attrPath.getLeaf() instanceof MethodInvocationTree inv) {
                invocationStart = sourcePos.getEndPosition(root, inv.getMethodSelect()) + 1;
            } else if (attrPath.getLeaf() instanceof NewClassTree nc) {
                invocationStart = sourcePos.getEndPosition(root, nc.getIdentifier()) + 1;
            } else {
                return NOT_SUPPORTED;
            }

            List<IndexedMember> overloads;
            List<? extends ExpressionTree> arguments;
            if (attrPath.getLeaf() instanceof MethodInvocationTree invoke) {
                overloads = resolveOverloads(task, root, attrPath, invoke);
                arguments = invoke.getArguments();
            } else if (attrPath.getLeaf() instanceof NewClassTree nc) {
                overloads = resolveConstructors(task, root, attrPath, nc);
                arguments = nc.getArguments();
            } else {
                return NOT_SUPPORTED;
            }

            if (overloads.isEmpty()) return NOT_SUPPORTED;
            var activeParameter = activeParameterAt(task, root, arguments, pruned, invocationStart, cursor);
            var activeSignature = selectOverload(task, attrPath, overloads, arguments, activeParameter);

            var signatures = new ArrayList<SignatureInformation>();
            for (var member : overloads) {
                var method = findSourceMethod(member);
                var info = new SignatureInformation();
                if (method != null) {
                    info.label = buildLabel(member, method);
                    info.parameters = buildParameters(method);
                } else {
                    // No source (e.g. a bytecode-only dependency jar): use the indexed signature.
                    info.label = buildLabelFromIndex(member);
                    info.parameters = buildParametersFromIndex(member);
                }
                signatures.add(info);
            }

            var parameterCount = signatures.get(activeSignature).parameters.size();
            activeParameter = Math.min(activeParameter, Math.max(0, parameterCount - 1));
            return new SignatureHelp(signatures, activeSignature, activeParameter);
        } catch (RuntimeException | AssertionError e) {
            LOG.fine(
                    String.format(
                            "[perf] signature_help_skip file=%s reason=%s message=%s",
                            file.getFileName(), e.getClass().getSimpleName(), e.getMessage()));
            return NOT_SUPPORTED;
        }
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

    private static int selectOverload(
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
        if (resolved == null || !(resolved.getEnclosingElement() instanceof TypeElement owner)) return closest;
        int compatible = -1;
        int compatibleCount = Integer.MAX_VALUE;
        for (var element : task.elements.getAllMembers(owner)) {
            if (!(element instanceof ExecutableElement method)) continue;
            int selected = overloadIndex(task, method, overloads);
            if (selected < 0) continue;
            int count = method.getParameters().size();
            int capacity = method.isVarArgs() ? Math.max(count, required) : count;
            if (compatible >= 0 && !((capacity >= required && capacity < compatibleCount)
                    || (compatibleCount < required && capacity > compatibleCount))) continue;
            if (acceptsArguments(task, invocation, method, arguments)) {
                compatible = selected;
                compatibleCount = capacity;
            }
        }
        return compatible >= 0 ? compatible : closest;
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
                    && target instanceof javax.lang.model.type.ArrayType array
                    && !task.types.isAssignable(type, target)) target = array.getComponentType();
            if (!task.types.isAssignable(type, target)) return false;
        }
        return true;
    }

    private List<IndexedMember> resolveOverloads(
            CompileTask task, CompilationUnitTree root, TreePath invokePath, MethodInvocationTree invoke) {
        var select = invoke.getMethodSelect();
        if (select instanceof IdentifierTree id) {
            // Prefer the compiler-resolved owner (handles static imports, cross-module, inherited).
            var resolved = resolveIdentifierOwner(task, invokePath, select, id.getName().toString());
            if (!resolved.isEmpty()) return resolved;
            var results = resolveUnqualifiedMethod(root, id);
            if (results.isEmpty()) {
                results = resolveStaticImportMethod(root, id.getName().toString());
            }
            return results;
        }
        if (select instanceof MemberSelectTree ms) {
            return resolveQualifiedMethod(task, invokePath, ms);
        }
        return List.of();
    }

    private List<IndexedMember> resolveIdentifierOwner(
            CompileTask task, TreePath invokePath, Tree methodSelect, String methodName) {
        var selectPath = new TreePath(invokePath, methodSelect);
        var element = task.trees.getElement(selectPath);
        if (element == null) return List.of();
        var owner = element.getEnclosingElement();
        if (!(owner instanceof TypeElement type)) return List.of();
        var ownerName = type.getQualifiedName().toString();
        var isStatic = element.getModifiers().contains(javax.lang.model.element.Modifier.STATIC);
        var overloads = index.methodOverloads(ownerName, methodName, isStatic);
        if (!overloads.isEmpty()) return overloads;
        // The element may be static-imported but recorded under instance context, or vice versa.
        return index.methodOverloads(ownerName, methodName, !isStatic);
    }

    private List<IndexedMember> resolveStaticImportMethod(CompilationUnitTree root, String methodName) {
        for (var imp : root.getImports()) {
            if (!imp.isStatic()) continue;
            var imported = imp.getQualifiedIdentifier().toString();
            if (imported.endsWith(".*")) {
                var ownerType = imported.substring(0, imported.length() - 2);
                var results = index.methodOverloads(ownerType, methodName, true);
                if (!results.isEmpty()) return results;
            } else if (imported.endsWith("." + methodName)) {
                var ownerType = imported.substring(0, imported.lastIndexOf('.'));
                var results = index.methodOverloads(ownerType, methodName, true);
                if (!results.isEmpty()) return results;
            }
        }
        return List.of();
    }

    private List<IndexedMember> resolveUnqualifiedMethod(CompilationUnitTree root, IdentifierTree id) {
        for (var decl : root.getTypeDecls()) {
            if (!(decl instanceof ClassTree ct)) continue;
            var qualifiedName = qualifiedClassName(root, ct);
            if (qualifiedName == null) continue;
            var results = index.methodOverloads(qualifiedName, id.getName().toString(), false);
            if (!results.isEmpty()) return results;
            results = index.methodOverloads(qualifiedName, id.getName().toString(), true);
            if (!results.isEmpty()) return results;
        }
        return List.of();
    }

    private List<IndexedMember> resolveQualifiedMethod(
            CompileTask task, TreePath invokePath, MemberSelectTree ms) {
        var receiverPath = new TreePath(invokePath, ms.getExpression());
        var qualifiedType = receiverTypeName(task, receiverPath);
        if (qualifiedType == null) return List.of();
        var isStatic = task.trees.getElement(receiverPath) instanceof TypeElement;
        return index.methodOverloads(qualifiedType, ms.getIdentifier().toString(), isStatic);
    }

    private List<IndexedMember> resolveConstructors(
            CompileTask task, CompilationUnitTree root, TreePath newClassPath, NewClassTree nc) {
        var qualifiedType = constructorTypeName(task, newClassPath, root, nc);
        if (qualifiedType == null) return List.of();
        return index.constructors(qualifiedType);
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

    /**
     * Qualified type of a {@code new X(...)}. For a diamond {@code new X<>(...)} with no inferable
     * arguments the attributed mirror is an error type, so fall back to resolving the raw type name
     * off the tree (the constructor overloads live on the raw type regardless of type arguments).
     */
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

    private MethodTree findSourceMethod(IndexedMember member) {
        var source = compiler.findAnywhere(member.ownerType);
        if (source.isEmpty()) return null;
        var task = compiler.parse(source.get());
        try {
            return FindHelper.findMethod(task, member.ownerType, member.name, member.erasedParameterTypes);
        } catch (RuntimeException notFound) {
            // Source exists but this overload isn't located (e.g. erased-type mismatch); fall back
            // to the indexed signature rather than aborting all overloads.
            return null;
        }
    }

    private String buildLabel(IndexedMember member, MethodTree method) {
        var sb = new StringBuilder();
        if (member.kind == CompletionItemKind.Constructor) {
            var owner = member.ownerType;
            sb.append(owner.substring(owner.lastIndexOf('.') + 1));
        } else {
            sb.append(method.getName());
        }
        sb.append('(');
        var params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(params.get(i).getType()).append(' ').append(params.get(i).getName());
        }
        sb.append(')');
        return sb.toString();
    }

    private List<ParameterInformation> buildParameters(MethodTree method) {
        var result = new ArrayList<ParameterInformation>();
        for (var p : method.getParameters()) {
            var info = new ParameterInformation();
            info.label = p.getType() + " " + p.getName();
            result.add(info);
        }
        return result;
    }

    /** Signature from the index member when no source is available (bytecode-only dependency). */
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
