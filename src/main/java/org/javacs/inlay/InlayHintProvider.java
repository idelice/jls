package org.javacs.inlay;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.ParseTask;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;

public class InlayHintProvider {
    private final CompilerProvider compiler;

    public InlayHintProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public List<InlayHint> inlayHints(Path file, Range range, boolean parameterNames, boolean varTypes) {
        if (!parameterNames && !varTypes) return List.of();
        try (var task = compiler.compile(file)) {
            var root = task.root();
            var lineMap = root.getLineMap();
            var trees = Trees.instance(task.task);
            var positions = trees.getSourcePositions();
            var hints = new ArrayList<InlayHint>();

            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree tree, Void p) {
                    if (parameterNames) {
                        addInvocationHints(tree.getArguments(), trees.getElement(getCurrentPath()));
                    }
                    return super.visitMethodInvocation(tree, p);
                }

                @Override
                public Void visitNewClass(NewClassTree tree, Void p) {
                    if (parameterNames) {
                        addInvocationHints(tree.getArguments(), trees.getElement(getCurrentPath()));
                    }
                    return super.visitNewClass(tree, p);
                }

                @Override
                public Void visitVariable(VariableTree tree, Void p) {
                    if (varTypes && isVarDeclaration(tree, positions, root)) {
                        addVarTypeHint(tree);
                    }
                    return super.visitVariable(tree, p);
                }

                private void addInvocationHints(List<? extends ExpressionTree> args, Element element) {
                    if (!(element instanceof ExecutableElement)) return;
                    var method = (ExecutableElement) element;
                    var params = method.getParameters();
                    var count = Math.min(params.size(), args.size());
                    for (int i = 0; i < count; i++) {
                        var arg = args.get(i);
                        var pos = positions.getStartPosition(root, arg);
                        if (pos < 0) continue;
                        var line = (int) lineMap.getLineNumber(pos) - 1;
                        var col = (int) lineMap.getColumnNumber(pos) - 1;
                        var hintPos = new Position(line, col);
                        if (!withinRange(range, hintPos)) continue;
                        var name = parameterName(task, method, i);
                        if (name.isEmpty()) continue;
                        var hint = new InlayHint(hintPos, name + ": ", 2);
                        hints.add(hint);
                    }
                }

                private void addVarTypeHint(VariableTree tree) {
                    var type = trees.getTypeMirror(getCurrentPath());
                    if (type == null && tree.getInitializer() != null) {
                        type = trees.getTypeMirror(new com.sun.source.util.TreePath(getCurrentPath(), tree.getInitializer()));
                    }
                    if (type == null) return;
                    var typeName = simplifyTypeName(type.toString());
                    var namePos = nameEndPosition(
                            tree, positions, lineMap, getCurrentPath().getCompilationUnit());
                    Position hintPos = namePos;
                    if (hintPos == null) {
                        var init = tree.getInitializer();
                        long initPos = init != null ? positions.getStartPosition(root, init) : -1;
                        if (initPos < 0) {
                            initPos = positions.getStartPosition(root, tree);
                        }
                        if (initPos >= 0) {
                            var line = (int) lineMap.getLineNumber(initPos) - 1;
                            var col = (int) lineMap.getColumnNumber(initPos) - 1;
                            hintPos = new Position(line, col);
                        }
                    }
                    if (hintPos == null) return;
                    if (!withinRange(range, hintPos)) return;
                    var hint = new InlayHint(hintPos, ": " + typeName, 1);
                    hints.add(hint);
                    LOG.info("Inlay var hint: " + tree.getName() + " -> " + typeName);
                }
            }.scan(root, null);

            LOG.fine("Inlay hints: " + hints.size() + " items for " + file.getFileName());
            return hints;
        }
    }

    private boolean isVarDeclaration(
            VariableTree tree, SourcePositions positions, com.sun.source.tree.CompilationUnitTree root) {
        var type = tree.getType();
        if (type != null) {
            if ("var".equals(type.toString())) return true;
            if (type instanceof IdentifierTree) {
                var id = (IdentifierTree) type;
                return "var".contentEquals(id.getName());
            }
        }
        // Fallback: inspect source between declaration start and variable name
        long start = positions.getStartPosition(root, tree);
        long end = positions.getEndPosition(root, tree);
        if (start < 0 || end < 0) return false;
        String name = tree.getName().toString();
        if (name.isEmpty()) return false;
        int namePos = FindHelper.findNameIn(root, name, (int) start, (int) end);
        if (namePos < 0) return false;
        try {
            var contents = root.getSourceFile().getCharContent(true);
            int scanStart = (int) start;
            int scanEnd = Math.min(namePos, contents.length());
            var prefix = contents.subSequence(scanStart, scanEnd).toString();
            return prefix.contains("var");
        } catch (Exception e) {
            return false;
        }
    }

    private String parameterName(org.javacs.CompileTask task, ExecutableElement method, int index) {
        var params = method.getParameters();
        if (index >= params.size()) return "";
        var name = params.get(index).getSimpleName().toString();
        if (!looksSynthetic(name)) return name;

        var resolved = resolveParameterNameFromSource(task, method, index);
        if (resolved != null && !resolved.equals(name)) {
            LOG.fine("Resolved parameter name " + name + " -> " + resolved + " for " + method.getSimpleName());
        }
        return resolved != null ? resolved : "";
    }

    private boolean looksSynthetic(String name) {
        if (name == null) return false;
        if (name.startsWith("arg") && name.length() > 3) {
            for (int i = 3; i < name.length(); i++) {
                if (!Character.isDigit(name.charAt(i))) return false;
            }
            return true;
        }
        return false;
    }

    private String resolveParameterNameFromSource(org.javacs.CompileTask task, ExecutableElement method, int index) {
        var enclosing = method.getEnclosingElement();
        if (!(enclosing instanceof TypeElement)) return null;
        var type = (TypeElement) enclosing;
        var className = type.getQualifiedName().toString();
        var source = compiler.findAnywhere(className);
        if (source.isEmpty()) return null;
        ParseTask parse;
        try {
            parse = compiler.parse(source.get());
        } catch (Exception e) {
            return null;
        }
        var erased = FindHelper.erasedParameterTypes(task, method);
        com.sun.source.tree.MethodTree tree;
        try {
            tree = FindHelper.findMethod(parse, className, method.getSimpleName().toString(), erased);
        } catch (Exception e) {
            return null;
        }
        if (tree == null) return null;
        if (index >= tree.getParameters().size()) return null;
        return tree.getParameters().get(index).getName().toString();
    }

    private String simplifyTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) return typeName;
        StringBuilder out = new StringBuilder(typeName.length());
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < typeName.length(); i++) {
            char c = typeName.charAt(i);
            if (isTypeChar(c)) {
                token.append(c);
            } else {
                appendSimplifiedToken(out, token);
                out.append(c);
            }
        }
        appendSimplifiedToken(out, token);
        return out.toString();
    }

    private boolean isTypeChar(char c) {
        return Character.isJavaIdentifierPart(c) || c == '.';
    }

    private void appendSimplifiedToken(StringBuilder out, StringBuilder token) {
        if (token.length() == 0) return;
        String t = token.toString();
        int lastDot = t.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < t.length() - 1) {
            out.append(t.substring(lastDot + 1));
        } else {
            out.append(t);
        }
        token.setLength(0);
    }

    private Position nameEndPosition(
            VariableTree tree,
            SourcePositions positions,
            com.sun.source.tree.LineMap lineMap,
            com.sun.source.tree.CompilationUnitTree root) {
        var start = positions.getStartPosition(root, tree);
        var end = positions.getEndPosition(root, tree);
        if (start < 0 || end < 0) return null;
        var name = tree.getName().toString();
        if (name.isEmpty()) return null;
        var pos = FindHelper.findNameIn(root, name, (int) start, (int) end);
        if (pos < 0) return null;
        var nameEnd = pos + name.length();
        var line = (int) lineMap.getLineNumber(nameEnd) - 1;
        var col = (int) lineMap.getColumnNumber(nameEnd) - 1;
        return new Position(line, col);
    }

    private boolean withinRange(Range range, Position pos) {
        if (range == null) return true;
        if (range == Range.NONE) return true;
        var start = range.start;
        var end = range.end;
        if (start == null || end == null) return true;
        if (pos.line < start.line || pos.line > end.line) return false;
        if (pos.line == start.line && pos.character < start.character) return false;
        if (pos.line == end.line && pos.character > end.character) return false;
        return true;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
