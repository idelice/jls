package org.javacs.provider;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import org.javacs.ClassFileParameterNames;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.LspPosition;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.Range;

/** Provides parameter-name inlay hints from javac's attributed tree. */
public class InlayHintProvider {
    private final CompilerProvider compiler;
    private final TypeIndexRouter typeIndex;

    public InlayHintProvider(CompilerProvider compiler, TypeIndexRouter typeIndex) {
        this.compiler = compiler;
        this.typeIndex = typeIndex;
    }

    public List<InlayHint> inlayHints(CompileTask task, Path file, Range range) {
        try {
            return scanTree(task, file, range);
        } catch (RuntimeException | AssertionError e) {
            return List.of();
        }
    }

    private List<InlayHint> scanTree(CompileTask task, Path file, Range range) {
            var root = task.root(file);
            if (root == null) return List.of();

            var positions = task.trees.getSourcePositions();
            long fileLength;
            try {
                fileLength = root.getSourceFile().getCharContent(false).length();
            } catch (IOException e) {
                fileLength = Long.MAX_VALUE / 2;
            }
            long rangeStart, rangeEnd;
            if (range == null) {
                rangeStart = 0;
                rangeEnd = fileLength;
            } else {
                var startOffset = LspPosition.offset(root, range.start);
                var endOffset = LspPosition.offset(root, range.end);
                rangeStart = startOffset < 0 ? 0 : startOffset;
                rangeEnd = endOffset < 0 ? fileLength : endOffset;
            }
            var hints = new ArrayList<InlayHint>();

            new TreePathScanner<Void, Void>() {
                @Override
                public Void scan(Tree tree, Void unused) {
                    if (tree == null) return null;
                    var start = positions.getStartPosition(root, tree);
                    var end = positions.getEndPosition(root, tree);
                    if (start >= 0 && end >= 0 && (end <= rangeStart || start >= rangeEnd)) {
                        return null;
                    }
                    return super.scan(tree, unused);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
                    if (isInsideGeneratedMember()) return null;
                    emitHints(invocation.getArguments());
                    return super.visitMethodInvocation(invocation, unused);
                }

                @Override
                public Void visitNewClass(NewClassTree constructor, Void unused) {
                    if (isInsideGeneratedMember()) return null;
                    emitHints(constructor.getArguments());
                    return super.visitNewClass(constructor, unused);
                }

                private boolean isInsideGeneratedMember() {
                    // Generated builder bodies are positioned at the owning source class. Do not
                    // expose their implementation details as hints on the class declaration.
                    // Start at the parent so a source-written call to a generated constructor or
                    // method still gets its normal inlay hints.
                    var path = getCurrentPath().getParentPath();
                    while (path != null) {
                        var element = task.trees.getElement(path);
                        if (element instanceof Symbol symbol
                                && (symbol.flags() & Flags.GENERATED_MEMBER) != 0) {
                            return true;
                        }
                        path = path.getParentPath();
                    }
                    return false;
                }

                private void emitHints(List<? extends ExpressionTree> arguments) {
                    if (arguments.isEmpty()) return;
                    var element = task.trees.getElement(getCurrentPath());
                    var parameterNames = element instanceof ExecutableElement method
                            ? parameterNames(task, method)
                            : constructorParameterNames();
                    if (parameterNames == null) return;
                    var limit = Math.min(parameterNames.length, arguments.size());
                    for (var i = 0; i < limit; i++) {
                        var name = parameterNames[i];
                        if (name == null || name.matches("arg\\d+")) continue;
                        var start = positions.getStartPosition(root, arguments.get(i));
                        if (start < rangeStart || start >= rangeEnd) continue;
                        hints.add(new InlayHint(LspPosition.position(root, start), name + ":", 2, true));
                    }
                }

                private String[] constructorParameterNames() {
                    if (!(getCurrentPath().getLeaf() instanceof NewClassTree constructor)) return null;
                    var typeName = typeIndex.resolveTypeName(constructor.getIdentifier().toString(), root).orElse(null);
                    if (typeName == null) return null;
                    var constructors = typeIndex.constructors(typeName).stream()
                            .filter(candidate -> candidate.parameterNames != null
                                    && candidate.parameterNames.length == constructor.getArguments().size())
                            .toList();
                    return constructors.size() == 1 ? constructors.getFirst().parameterNames : null;
                }

                private String[] parameterNames(CompileTask compileTask, ExecutableElement method) {
                    var names = method.getParameters().stream()
                            .map(parameter -> parameter.getSimpleName().toString())
                            .toArray(String[]::new);
                    if (!hasGeneratedNames(names)) return names;
                    if (!(method.getEnclosingElement() instanceof TypeElement owner)) return names;
                    var ownerName = owner.getQualifiedName().toString();
                    var classBytes = compiler.findClassFile(ownerName);
                    if (classBytes.isEmpty()) return names;
                    var isStatic = method.getModifiers().contains(Modifier.STATIC);
                    var erasedParams = FindHelper.erasedParameterTypes(compileTask, method);
                    var lvtNames = ClassFileParameterNames.read(
                            classBytes.get(), method.getSimpleName().toString(), erasedParams, isStatic);
                    return lvtNames != null ? lvtNames : names;
                }

                private boolean hasGeneratedNames(String[] names) {
                    for (var name : names) {
                        if (name.matches("arg\\d+")) return true;
                    }
                    return false;
                }
            }.scan(root, null);

            return hints;
    }
}
