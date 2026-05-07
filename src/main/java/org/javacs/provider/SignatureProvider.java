package org.javacs.provider;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Predicate;
import java.util.logging.Logger;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.*;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.FindHelper;
import org.javacs.MarkdownHelper;
import org.javacs.ParseTask;
import org.javacs.completion.FindInvocationAt;
import org.javacs.completion.ScopeHelper;
import org.javacs.hover.ShortTypePrinter;
import org.javacs.lsp.ParameterInformation;
import org.javacs.lsp.SignatureHelp;
import org.javacs.lsp.SignatureInformation;

public class SignatureProvider {
    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;

    public static final SignatureHelp NOT_SUPPORTED = new SignatureHelp(List.of(), -1, -1);

    public SignatureProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public SignatureHelp signatureHelp(Path file, int line, int column) {
        // Skip annotation processors when Lombok is absent — cheaper compile.
        try (var task = compiler.lombokPresentOnClasspath()
                ? compiler.compileFastWithProcessors(file)
                : compiler.compileFast(file)) {
            var root = task.root(file);
            String content;
            try {
                content = root.getSourceFile().getCharContent(true).toString();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            long cursor = FileStore.offset(content, line, column);
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

            // activeParameter computed from text — no extra ATTR needed for comma counting.
            var activeParameter = activeParameterFromText(content, invocationStart, cursor);

            List<SignatureInformation> signatures;
            int activeSignature;
            if (attrPath.getLeaf() instanceof MethodInvocationTree invoke) {
                var overloads = methodOverloads(task, root, invoke);
                signatures = buildSignatures(task, overloads);
                activeSignature = activeSignature(task, attrPath, invoke.getArguments(), overloads);
            } else if (attrPath.getLeaf() instanceof NewClassTree invoke) {
                var overloads = constructorOverloads(task, root, invoke);
                signatures = buildSignatures(task, overloads);
                activeSignature = activeSignature(task, attrPath, invoke.getArguments(), overloads);
            } else {
                return NOT_SUPPORTED;
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

    /**
     * Count argument index at cursor using raw text. Counts commas at depth 0 (not nested in inner
     * parens/brackets) starting from openParen, which points to the first character inside the call
     * (i.e., already past the opening '('). Avoids any compiler involvement.
     */
    private static int activeParameterFromText(String content, long openParen, long cursor) {
        // openParen is the position of the first char after '(' — already inside the call.
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

    /**
     * Build SignatureInformation list from overloads, deduplicating source file parses per
     * declaring class so each source file is parsed at most once per request.
     */
    private List<SignatureInformation> buildSignatures(CompileTask task, List<ExecutableElement> overloads) {
        var parsedSources = new HashMap<String, Optional<ParseTask>>();
        var signatures = new ArrayList<SignatureInformation>();
        for (var method : overloads) {
            var info = info(method);
            addSourceInfoDeduped(task, method, info, parsedSources);
            addFancyLabel(info);
            signatures.add(info);
        }
        return signatures;
    }

    private List<ExecutableElement> methodOverloads(
            CompileTask task, CompilationUnitTree root, MethodInvocationTree method) {
        if (method.getMethodSelect() instanceof IdentifierTree) {
            var id = (IdentifierTree) method.getMethodSelect();
            return scopeOverloads(task, root, id);
        }
        if (method.getMethodSelect() instanceof MemberSelectTree) {
            var select = (MemberSelectTree) method.getMethodSelect();
            return memberOverloads(task, root, select);
        }
        throw new RuntimeException(method.getMethodSelect().toString());
    }

    private List<ExecutableElement> scopeOverloads(CompileTask task, CompilationUnitTree root, IdentifierTree method) {
        var trees = Trees.instance(task.task);
        var path = trees.getPath(root, method);
        var scope = trees.getScope(path);
        var list = new ArrayList<ExecutableElement>();
        Predicate<CharSequence> filter = name -> method.getName().contentEquals(name);
        // TODO add static imports
        for (var member : ScopeHelper.scopeMembers(task, scope, filter)) {
            if (member.getKind() == ElementKind.METHOD) {
                list.add((ExecutableElement) member);
            }
        }
        return list;
    }

    private List<ExecutableElement> memberOverloads(
            CompileTask task, CompilationUnitTree root, MemberSelectTree method) {
        var trees = Trees.instance(task.task);
        var path = trees.getPath(root, method.getExpression());
        var isStatic = trees.getElement(path) instanceof TypeElement;
        var scope = trees.getScope(path);
        var type = typeElement(trees.getTypeMirror(path));
        if (type == null) return List.of();
        var list = new ArrayList<ExecutableElement>();
        for (var member : task.task.getElements().getAllMembers(type)) {
            if (member.getKind() != ElementKind.METHOD) continue;
            if (!member.getSimpleName().contentEquals(method.getIdentifier())) continue;
            if (isStatic != member.getModifiers().contains(Modifier.STATIC)) continue;
            if (!trees.isAccessible(scope, member, (DeclaredType) type.asType())) continue;
            list.add((ExecutableElement) member);
        }
        return list;
    }

    private TypeElement typeElement(TypeMirror type) {
        if (type instanceof DeclaredType) {
            var declared = (DeclaredType) type;
            return (TypeElement) declared.asElement();
        }
        if (type instanceof TypeVariable) {
            var variable = (TypeVariable) type;
            return typeElement(variable.getUpperBound());
        }
        return null;
    }

    private List<ExecutableElement> constructorOverloads(
            CompileTask task, CompilationUnitTree root, NewClassTree method) {
        var trees = Trees.instance(task.task);
        var path = trees.getPath(root, method.getIdentifier());
        var scope = trees.getScope(path);
        var type = (TypeElement) trees.getElement(path);
        var list = new ArrayList<ExecutableElement>();
        for (var member : task.task.getElements().getAllMembers(type)) {
            if (member.getKind() != ElementKind.CONSTRUCTOR) continue;
            if (!trees.isAccessible(scope, member, (DeclaredType) type.asType())) continue;
            list.add((ExecutableElement) member);
        }
        return list;
    }

    private SignatureInformation info(ExecutableElement method) {
        var info = new SignatureInformation();
        info.label = method.getSimpleName().toString();
        if (method.getKind() == ElementKind.CONSTRUCTOR) {
            info.label = method.getEnclosingElement().getSimpleName().toString();
        }
        info.parameters = parameters(method);
        return info;
    }

    private List<ParameterInformation> parameters(ExecutableElement method) {
        var list = new ArrayList<ParameterInformation>();
        for (var p : method.getParameters()) {
            list.add(parameter(p));
        }
        return list;
    }

    private ParameterInformation parameter(VariableElement p) {
        var info = new ParameterInformation();
        info.label = ShortTypePrinter.NO_PACKAGE.print(p.asType()) + " " + p.getSimpleName();
        return info;
    }

    /**
     * Populate source-derived documentation and parameter names, reusing an already-parsed source
     * file for the declaring class when available. parsedSources is keyed by qualified class name
     * and accumulates across overloads so each source file is parsed at most once per request.
     */
    private void addSourceInfoDeduped(
            CompileTask task,
            ExecutableElement method,
            SignatureInformation info,
            HashMap<String, Optional<ParseTask>> parsedSources) {
        var type = (TypeElement) method.getEnclosingElement();
        var className = type.getQualifiedName().toString();
        var methodName = method.getSimpleName().toString();
        var erasedParameterTypes = FindHelper.erasedParameterTypes(task, method);
        var parse = parsedSources.computeIfAbsent(className, key -> {
            var sourceFile = compiler.findAnywhere(key);
            return sourceFile.map(compiler::parse);
        });
        if (parse.isEmpty()) return;
        try {
            var source = FindHelper.findMethod(parse.get(), className, methodName, erasedParameterTypes);
            var path = Trees.instance(task.task).getPath(parse.get().root(), source);
            var docTree = DocTrees.instance(task.task).getDocCommentTree(path);
            if (docTree != null) {
                info.documentation = MarkdownHelper.asMarkupContent(docTree);
            }
            info.parameters = parametersFromSource(source);
        } catch (RuntimeException missingSourceMethod) {
            // Generated methods (for example Lombok accessors) have no source MethodTree.
        }
    }

    private void addFancyLabel(SignatureInformation info) {
        var join = new StringJoiner(", ");
        for (var p : info.parameters) {
            join.add(p.label);
        }
        info.label = info.label + "(" + join + ")";
    }

    private List<ParameterInformation> parametersFromSource(MethodTree source) {
        var list = new ArrayList<ParameterInformation>();
        for (var p : source.getParameters()) {
            var info = new ParameterInformation();
            info.label = p.getType() + " " + p.getName();
            list.add(info);
        }
        return list;
    }

    private int activeSignature(
            CompileTask task,
            TreePath invocation,
            List<? extends ExpressionTree> arguments,
            List<ExecutableElement> overloads) {
        for (var i = 0; i < overloads.size(); i++) {
            if (isCompatible(task, invocation, arguments, overloads.get(i))) {
                return i;
            }
        }
        return 0;
    }

    private boolean isCompatible(
            CompileTask task,
            TreePath invocation,
            List<? extends ExpressionTree> arguments,
            ExecutableElement overload) {
        if (arguments.size() > overload.getParameters().size()) return false;
        for (var i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            var argumentType = Trees.instance(task.task).getTypeMirror(new TreePath(invocation, argument));
            var parameterType = overload.getParameters().get(i).asType();
            if (!isCompatible(task, argumentType, parameterType)) return false;
        }
        return true;
    }

    private boolean isCompatible(CompileTask task, TypeMirror argument, TypeMirror parameter) {
        if (argument instanceof ErrorType) return true;
        if (argument instanceof PrimitiveType) {
            argument = task.task.getTypes().boxedClass((PrimitiveType) argument).asType();
        }
        if (parameter instanceof PrimitiveType) {
            parameter = task.task.getTypes().boxedClass((PrimitiveType) parameter).asType();
        }
        if (argument instanceof ArrayType) {
            if (!(parameter instanceof ArrayType)) return false;
            var argumentA = (ArrayType) argument;
            var parameterA = (ArrayType) parameter;
            return isCompatible(task, argumentA.getComponentType(), parameterA.getComponentType());
        }
        if (argument instanceof DeclaredType) {
            if (!(parameter instanceof DeclaredType)) return false;
            argument = task.task.getTypes().erasure(argument);
            parameter = task.task.getTypes().erasure(parameter);
            return argument.toString().equals(parameter.toString());
        }
        return true;
    }
}
