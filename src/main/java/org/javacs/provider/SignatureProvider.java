package org.javacs.provider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.javacs.FindHelper;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.ParseTask;
import org.javacs.completion.FindInvocationAt;
import org.javacs.index.IndexedMember;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.ParameterInformation;
import org.javacs.lsp.SignatureHelp;
import org.javacs.lsp.SignatureInformation;
import org.javacs.resolve.ParseTypeResolver;

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
        try {
            var root = parse.root();
            String content;
            try {
                content = root.getSourceFile().getCharContent(true).toString();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            long cursor = FileStore.offset(content, line, column);
            var attrPath = new FindInvocationAt(parse.task()).scan(root, (Long) cursor);
            if (attrPath == null) return NOT_SUPPORTED;

            var sourcePos = Trees.instance(parse.task()).getSourcePositions();
            long invocationStart;
            if (attrPath.getLeaf() instanceof MethodInvocationTree inv) {
                invocationStart = sourcePos.getEndPosition(root, inv.getMethodSelect()) + 1;
            } else if (attrPath.getLeaf() instanceof NewClassTree nc) {
                invocationStart = sourcePos.getEndPosition(root, nc.getIdentifier()) + 1;
            } else {
                return NOT_SUPPORTED;
            }

            var activeParameter = activeParameterFromText(content, invocationStart, cursor);

            List<IndexedMember> overloads;
            int activeSignature;
            if (attrPath.getLeaf() instanceof MethodInvocationTree invoke) {
                overloads = resolveOverloads(parse, root, invoke, cursor);
                activeSignature = Math.min(overloads.size() - 1, Math.max(0, activeParameter));
            } else if (attrPath.getLeaf() instanceof NewClassTree nc) {
                overloads = resolveConstructors(parse, root, nc, cursor);
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
            ParseTask parse, CompilationUnitTree root, MethodInvocationTree invoke, long cursor) {
        var select = invoke.getMethodSelect();
        if (select instanceof IdentifierTree id) {
            var results = resolveUnqualifiedMethod(root, id, parse, cursor);
            if (results.isEmpty()) {
                results = resolveStaticImportMethod(root, id.getName().toString());
            }
            return results;
        }
        if (select instanceof MemberSelectTree ms) {
            return resolveQualifiedMethod(parse, ms, cursor);
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

    private List<IndexedMember> resolveUnqualifiedMethod(
            CompilationUnitTree root, IdentifierTree id, ParseTask parse, long cursor) {
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
            ParseTask parse, MemberSelectTree ms, long cursor) {
        var resolver = new ParseTypeResolver(parse, compiler, index, cursor);
        var resolved = resolver.resolveExpression(ms.getExpression());
        if (resolved.isPresent()) {
            return index.methodOverloads(
                    resolved.get().qualifiedType(),
                    ms.getIdentifier().toString(),
                    resolved.get().staticContext());
        }
        return List.of();
    }

    private List<IndexedMember> resolveConstructors(
            ParseTask parse, CompilationUnitTree root, NewClassTree nc, long cursor) {
        var resolver = new ParseTypeResolver(parse, compiler, index, cursor);
        var resolved = resolver.resolveExpression(nc.getIdentifier());
        if (resolved.isPresent()) {
            var found = index.constructors(resolved.get().qualifiedType());
            if (!found.isEmpty()) return found;
        }
        return List.of();
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
