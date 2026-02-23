package org.javacs.hint;

import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.ParseTask;
import org.javacs.hover.ShortTypePrinter;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.InlayHintKind;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;

public class InlayHintProvider {
    public List<InlayHint> inlayHints(CompilerProvider compiler, Path file, Range range) {
        try (var task = compiler.compile(file)) {
            var root = task.root(file);
            var trees = Trees.instance(task.task);
            var positions = trees.getSourcePositions();
            var lines = root.getLineMap();
            var source = root.getSourceFile().getCharContent(true).toString();
            var typeUtils = task.task.getTypes();
            var filter = asOffsetRange(lines, range);
            var result = new ArrayList<InlayHint>();
            var parameterNamesCache = new HashMap<ExecutableElement, List<String>>();

            class FindInlayHints extends TreePathScanner<Void, Void> {
                @Override
                public Void visitVariable(VariableTree variable, Void aVoid) {
                    // Skip redundant hints when initializer already spells out the type.
                    if (isRedundantVarInitializer(variable.getInitializer())) {
                        return super.visitVariable(variable, aVoid);
                    }
                    var declarationStart = positions.getStartPosition(root, variable);
                    var declarationEnd = positions.getEndPosition(root, variable);
                    if (declarationEnd < 0) declarationEnd = declarationStart;
                    if (!isVarDeclaration(variable, source, declarationStart, declarationEnd)) {
                        return super.visitVariable(variable, aVoid);
                    }
                    if (!intersects(filter[0], filter[1], declarationStart, declarationEnd)) {
                        return super.visitVariable(variable, aVoid);
                    }

                    TypeMirror inferred = null;
                    var element = trees.getElement(getCurrentPath());
                    if (element instanceof javax.lang.model.element.VariableElement) {
                        inferred = ((javax.lang.model.element.VariableElement) element).asType();
                    }
                    if (inferred == null || inferred.getKind() == TypeKind.ERROR) {
                        inferred = trees.getTypeMirror(getCurrentPath());
                    }
                    if (inferred == null || inferred.getKind() == TypeKind.ERROR) {
                        return super.visitVariable(variable, aVoid);
                    }

                    var type = variable.getType();
                    var insertAt = positions.getEndPosition(root, type);
                    if (insertAt < 0) {
                        insertAt = findVarTokenEndOffset(variable, source, declarationStart, declarationEnd);
                    }
                    if (insertAt < 0) return super.visitVariable(variable, aVoid);

                    var line = (int) lines.getLineNumber(insertAt);
                    var column = (int) lines.getColumnNumber(insertAt);
                    if (line <= 0 || column <= 0) return super.visitVariable(variable, aVoid);

                    var hint = new InlayHint(new Position(line - 1, column - 1), ": " + ShortTypePrinter.NO_PACKAGE.print(inferred));
                    hint.kind = InlayHintKind.Type;
                    hint.paddingLeft = true;
                    result.add(hint);
                    return super.visitVariable(variable, aVoid);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree invocation, Void aVoid) {
                    var executable = resolveMethodExecutable(getCurrentPath(), invocation);
                    addParameterNameHints(invocation.getArguments(), executable);
                    return super.visitMethodInvocation(invocation, aVoid);
                }

                @Override
                public Void visitNewClass(NewClassTree invocation, Void aVoid) {
                    var executable = resolveConstructorExecutable(getCurrentPath(), invocation);
                    addParameterNameHints(invocation.getArguments(), executable);
                    return super.visitNewClass(invocation, aVoid);
                }

                private void addParameterNameHints(List<? extends ExpressionTree> arguments, ExecutableElement executable) {
                    if (executable == null || arguments.isEmpty()) return;
                    var names =
                            parameterNamesCache.computeIfAbsent(
                                    executable, key -> parameterNamesFor(compiler, key, typeUtils));
                    if (names.isEmpty()) return;

                    for (var i = 0; i < arguments.size(); i++) {
                        var argument = arguments.get(i);
                        var start = positions.getStartPosition(root, argument);
                        var end = positions.getEndPosition(root, argument);
                        if (end < 0) end = start;
                        if (!intersects(filter[0], filter[1], start, end)) continue;

                        var parameterName = parameterNameAt(names, executable, i);
                        if (parameterName == null) continue;

                        var line = (int) lines.getLineNumber(start);
                        var column = (int) lines.getColumnNumber(start);
                        if (line <= 0 || column <= 0) continue;

                        var hint = new InlayHint(new Position(line - 1, column - 1), parameterName + ":");
                        hint.kind = InlayHintKind.Parameter;
                        hint.paddingRight = true;
                        result.add(hint);
                    }
                }

                private ExecutableElement resolveMethodExecutable(TreePath invocationPath, MethodInvocationTree invocation) {
                    var byInvocation = trees.getElement(invocationPath);
                    if (byInvocation instanceof ExecutableElement) return (ExecutableElement) byInvocation;
                    var bySelect = trees.getElement(new TreePath(invocationPath, invocation.getMethodSelect()));
                    if (bySelect instanceof ExecutableElement) return (ExecutableElement) bySelect;
                    return null;
                }

                private ExecutableElement resolveConstructorExecutable(TreePath invocationPath, NewClassTree invocation) {
                    var byInvocation = trees.getElement(invocationPath);
                    if (byInvocation instanceof ExecutableElement) return (ExecutableElement) byInvocation;

                    var byIdentifier = trees.getElement(new TreePath(invocationPath, invocation.getIdentifier()));
                    if (byIdentifier instanceof ExecutableElement) return (ExecutableElement) byIdentifier;
                    if (!(byIdentifier instanceof TypeElement)) return null;

                    var type = (TypeElement) byIdentifier;
                    var constructors = new ArrayList<ExecutableElement>();
                    for (var member : type.getEnclosedElements()) {
                        if (member.getKind() != ElementKind.CONSTRUCTOR) continue;
                        constructors.add((ExecutableElement) member);
                    }
                    if (constructors.isEmpty()) return null;
                    if (constructors.size() == 1) return constructors.get(0);

                    var argCount = invocation.getArguments().size();
                    for (var ctor : constructors) {
                        if (isCompatible(invocationPath, invocation.getArguments(), ctor)) {
                            return ctor;
                        }
                    }
                    for (var ctor : constructors) {
                        if (ctor.isVarArgs()) {
                            if (argCount >= ctor.getParameters().size() - 1) return ctor;
                        } else if (argCount == ctor.getParameters().size()) {
                            return ctor;
                        }
                    }
                    return constructors.get(0);
                }

                private boolean isCompatible(
                        TreePath invocationPath, List<? extends ExpressionTree> arguments, ExecutableElement ctor) {
                    var params = ctor.getParameters();
                    if (!ctor.isVarArgs() && arguments.size() != params.size()) return false;
                    if (ctor.isVarArgs() && arguments.size() < params.size() - 1) return false;

                    for (var i = 0; i < arguments.size(); i++) {
                        var argument = arguments.get(i);
                        var argType = trees.getTypeMirror(new TreePath(invocationPath, argument));
                        TypeMirror paramType;
                        if (ctor.isVarArgs() && i >= params.size() - 1) {
                            var varArgArray = params.get(params.size() - 1).asType();
                            if (!(varArgArray instanceof javax.lang.model.type.ArrayType)) return false;
                            paramType = ((javax.lang.model.type.ArrayType) varArgArray).getComponentType();
                        } else {
                            paramType = params.get(i).asType();
                        }
                        if (!isAssignable(argType, paramType)) return false;
                    }
                    return true;
                }

                private boolean isAssignable(TypeMirror argument, TypeMirror parameter) {
                    if (argument == null || parameter == null) return false;
                    if (argument.getKind() == TypeKind.NULL) {
                        return parameter.getKind() != TypeKind.BOOLEAN
                                && parameter.getKind() != TypeKind.BYTE
                                && parameter.getKind() != TypeKind.SHORT
                                && parameter.getKind() != TypeKind.INT
                                && parameter.getKind() != TypeKind.LONG
                                && parameter.getKind() != TypeKind.CHAR
                                && parameter.getKind() != TypeKind.FLOAT
                                && parameter.getKind() != TypeKind.DOUBLE;
                    }
                    var types = task.task.getTypes();
                    var a = argument;
                    var p = parameter;
                    if (a.getKind().isPrimitive()) {
                        a = types.boxedClass((javax.lang.model.type.PrimitiveType) a).asType();
                    }
                    if (p.getKind().isPrimitive()) {
                        p = types.boxedClass((javax.lang.model.type.PrimitiveType) p).asType();
                    }
                    return types.isAssignable(types.erasure(a), types.erasure(p));
                }
            }

            new FindInlayHints().scan(root, null);
            LOG.fine("[inlay-hints] computed " + result.size() + " hints for " + file.getFileName());
            return result;
        } catch (Exception e) {
            LOG.warning("Failed to compute inlay hints for " + file + ": " + e.getMessage());
            return List.of();
        }
    }

    private String parameterNameAt(List<String> names, ExecutableElement method, int argumentIndex) {
        if (names.isEmpty()) return null;
        int parameterIndex = argumentIndex;
        if (method.isVarArgs() && parameterIndex >= names.size()) {
            parameterIndex = names.size() - 1;
        }
        if (parameterIndex < 0 || parameterIndex >= names.size()) return null;
        var name = names.get(parameterIndex);
        if (name == null || name.isBlank() || SYNTHETIC_PARAMETER.matcher(name).matches()) {
            return null;
        }
        return name;
    }

    private List<String> parameterNamesFor(
            CompilerProvider compiler, ExecutableElement method, javax.lang.model.util.Types typeUtils) {
        var names = new ArrayList<String>();
        for (var p : method.getParameters()) {
            names.add(p.getSimpleName().toString());
        }
        if (names.stream().noneMatch(name -> SYNTHETIC_PARAMETER.matcher(name).matches())) {
            return names;
        }
        var sourceNames = sourceParameterNames(compiler, method, typeUtils);
        if (!sourceNames.isEmpty()) {
            return sourceNames;
        }
        return names;
    }

    private List<String> sourceParameterNames(
            CompilerProvider compiler, ExecutableElement method, javax.lang.model.util.Types typeUtils) {
        try {
            var enclosing = method.getEnclosingElement();
            if (!(enclosing instanceof TypeElement)) return List.of();
            var type = (TypeElement) enclosing;
            var className = type.getQualifiedName().toString();
            Optional<JavaFileObject> source = compiler.findAnywhere(className);
            if (source.isEmpty()) return List.of();
            ParseTask parse = compiler.parse(source.get());
            var erased = erasedParameterTypes(method, typeUtils);
            MethodTree treeMethod;
            if (method.getKind() == ElementKind.CONSTRUCTOR) {
                treeMethod = findConstructorOrNull(parse, className, erased);
            } else {
                treeMethod = FindHelper.findMethodOrNull(parse, className, method.getSimpleName().toString(), erased);
            }
            if (treeMethod == null) return List.of();
            var names = new ArrayList<String>();
            for (var p : treeMethod.getParameters()) {
                names.add(p.getName().toString());
            }
            return names;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String[] erasedParameterTypes(ExecutableElement method, javax.lang.model.util.Types typeUtils) {
        var erased = new String[method.getParameters().size()];
        for (var i = 0; i < method.getParameters().size(); i++) {
            var parameterType = method.getParameters().get(i).asType();
            erased[i] = typeUtils.erasure(parameterType).toString();
        }
        return erased;
    }

    private MethodTree findConstructorOrNull(ParseTask parse, String className, String[] erasedParameterTypes) {
        ClassTree classTree = FindHelper.findType(parse, className);
        if (classTree == null) return null;
        for (var member : classTree.getMembers()) {
            if (member.getKind() != Tree.Kind.METHOD) continue;
            var method = (MethodTree) member;
            if (!method.getName().contentEquals("<init>")) continue;
            if (!isSameMethodType(method, erasedParameterTypes)) continue;
            return method;
        }
        return null;
    }

    private boolean isSameMethodType(MethodTree candidate, String[] erasedParameterTypes) {
        if (candidate.getParameters().size() != erasedParameterTypes.length) return false;
        for (var i = 0; i < candidate.getParameters().size(); i++) {
            if (!typeMatches(candidate.getParameters().get(i).getType(), erasedParameterTypes[i])) return false;
        }
        return true;
    }

    private boolean typeMatches(Tree candidate, String erasedType) {
        if (candidate instanceof ParameterizedTypeTree) {
            return typeMatches(((ParameterizedTypeTree) candidate).getType(), erasedType);
        }
        if (candidate instanceof PrimitiveTypeTree) {
            return candidate.toString().equals(erasedType);
        }
        if (candidate instanceof IdentifierTree) {
            var simpleName = candidate.toString();
            return erasedType.equals(simpleName) || erasedType.endsWith("." + simpleName);
        }
        if (candidate instanceof MemberSelectTree) {
            var selected = candidate.toString();
            return erasedType.equals(selected)
                    || erasedType.endsWith("." + selected)
                    || selected.endsWith("." + erasedType);
        }
        if (candidate instanceof ArrayTypeTree) {
            var array = (ArrayTypeTree) candidate;
            if (!erasedType.endsWith("[]")) return false;
            var erasedElement = erasedType.substring(0, erasedType.length() - "[]".length());
            return typeMatches(array.getType(), erasedElement);
        }
        return true;
    }

    private boolean isVarKeyword(Tree type) {
        return type != null && "var".equals(type.toString());
    }

    private boolean isRedundantVarInitializer(ExpressionTree initializer) {
        if (initializer == null) return false;
        switch (initializer.getKind()) {
            case NEW_CLASS:
            case NEW_ARRAY:
            case TYPE_CAST:
                return true;
            case MEMBER_SELECT:
                var select = (MemberSelectTree) initializer;
                return select.getIdentifier().contentEquals("class");
            default:
                return false;
        }
    }

    private boolean isVarDeclaration(VariableTree variable, String source, long declarationStart, long declarationEnd) {
        if (isVarKeyword(variable.getType())) return true;
        var name = variable.getName().toString();
        if (name.isBlank() || declarationStart < 0) return false;
        var start = (int) declarationStart;
        var end = declarationEnd > declarationStart ? (int) declarationEnd : start + 120;
        end = Math.min(end + 1, source.length());
        if (start >= end || start < 0 || start >= source.length()) return false;
        var snippet = source.substring(start, end);
        var nameIndex = snippet.indexOf(name);
        if (nameIndex < 0) return false;
        var beforeName = snippet.substring(0, nameIndex);
        return VAR_KEYWORD.matcher(beforeName).find();
    }

    private long findVarTokenEndOffset(VariableTree variable, String source, long declarationStart, long declarationEnd) {
        var name = variable.getName().toString();
        if (name.isBlank() || declarationStart < 0) return -1;
        var start = (int) declarationStart;
        var end = declarationEnd > declarationStart ? (int) declarationEnd : start + 120;
        end = Math.min(end + 1, source.length());
        if (start >= end || start < 0 || start >= source.length()) return -1;
        var snippet = source.substring(start, end);
        var nameIndex = snippet.indexOf(name);
        if (nameIndex < 0) return -1;
        var beforeName = snippet.substring(0, nameIndex);
        var matcher = VAR_KEYWORD.matcher(beforeName);
        if (!matcher.find()) return -1;
        return start + matcher.end();
    }

    private static long[] asOffsetRange(com.sun.source.tree.LineMap lines, Range range) {
        if (range == null || range == Range.NONE || range.start == null || range.end == null) {
            return new long[] {0, Long.MAX_VALUE};
        }
        try {
            var start = lines.getPosition(range.start.line + 1, range.start.character + 1);
            var end = lines.getPosition(range.end.line + 1, range.end.character + 1);
            if (start < 0 || end < 0) return new long[] {0, Long.MAX_VALUE};
            if (end < start) return new long[] {end, start};
            return new long[] {start, end};
        } catch (Exception ignored) {
            return new long[] {0, Long.MAX_VALUE};
        }
    }

    private static boolean intersects(long rangeStart, long rangeEnd, long start, long end) {
        if (start < 0 || end < 0) return false;
        return start <= rangeEnd && rangeStart <= end;
    }

    private static final Pattern SYNTHETIC_PARAMETER = Pattern.compile("arg\\d+");
    private static final Pattern VAR_KEYWORD = Pattern.compile("\\bvar\\b");
    private static final Logger LOG = Logger.getLogger("main");
}
