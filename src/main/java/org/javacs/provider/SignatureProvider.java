package org.javacs.provider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
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
import java.util.List;
import java.util.logging.Logger;
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

            var activeParameter = activeParameterFromText(pruned, invocationStart, cursor);

            List<IndexedMember> overloads;
            int activeSignature;
            if (attrPath.getLeaf() instanceof MethodInvocationTree invoke) {
                overloads = resolveOverloads(task, root, attrPath, invoke);
                activeSignature = Math.min(overloads.size() - 1, Math.max(0, activeParameter));
            } else if (attrPath.getLeaf() instanceof NewClassTree nc) {
                overloads = resolveConstructors(task, root, attrPath, nc);
                activeSignature = Math.min(overloads.size() - 1, Math.max(0, activeParameter));
            } else {
                return NOT_SUPPORTED;
            }

            var signatures = new ArrayList<SignatureInformation>();
            for (var member : overloads) {
                var method = findSourceMethod(member);
                var info = new SignatureInformation();
                info.label = buildLabel(member, method);
                info.parameters = buildParameters(method);
                signatures.add(info);
            }

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

    private static int activeParameterFromText(String content, long openParen, long cursor) {
        int depth = 0;
        int commas = 0;
        for (long i = openParen; i < cursor && i < content.length(); i++) {
            char c = content.charAt((int) i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if ((c == ')' || c == ']' || c == '}') && depth > 0) depth--;
            else if (c == ',' && depth == 0) commas++;
        }
        return commas;
    }

    private List<IndexedMember> resolveOverloads(
            CompileTask task, CompilationUnitTree root, TreePath invokePath, MethodInvocationTree invoke) {
        var select = invoke.getMethodSelect();
        if (select instanceof IdentifierTree id) {
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
        if (source.isEmpty()) throw new RuntimeException("no source");
        var task = compiler.parse(source.get());
        return FindHelper.findMethod(task, member.ownerType, member.name, member.erasedParameterTypes);
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
}
