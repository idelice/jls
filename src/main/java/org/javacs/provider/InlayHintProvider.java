package org.javacs.provider;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.lang.model.element.ExecutableElement;
import org.javacs.CompilerProvider;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;

public class InlayHintProvider {
    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;

    public InlayHintProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public List<InlayHint> inlayHints(Path file, Range range) {
        try (var task = compiler.lombokPresentOnClasspath() ? compiler.compileFastWithProcessors(file) : compiler.compileFast(file)) {
            var root = task.root(file);
            var lineMap = root.getLineMap();
            var trees = Trees.instance(task.task);
            var positions = trees.getSourcePositions();

            long fileLen;
            try {
                fileLen = root.getSourceFile().getCharContent(false).length();
            } catch (java.io.IOException e) {
                fileLen = Long.MAX_VALUE / 2;
            }
            long rangeStart = lineMap.getPosition(range.start.line + 1, range.start.character + 1);
            long rangeEndTmp;
            try {
                rangeEndTmp = lineMap.getPosition(range.end.line + 1, range.end.character + 1);
                if (rangeEndTmp < 0) rangeEndTmp = fileLen;
            } catch (ArrayIndexOutOfBoundsException e) {
                rangeEndTmp = fileLen;
            }
            final long rangeEnd = rangeEndTmp;

            var hints = new ArrayList<InlayHint>();

            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
                    var args = invocation.getArguments();
                    if (!args.isEmpty()) {
                        long invPos = positions.getStartPosition(root, invocation);
                        long firstArgPos = positions.getStartPosition(root, args.get(0));
                        // Skip synthetic/generated invocations (e.g. Lombok-generated code where
                        // all AST node positions collapse to the triggering annotation position).
                        if (invPos >= 0 && invPos != firstArgPos) {
                            processCall(trees.getElement(getCurrentPath()), args);
                        }
                    }
                    return super.visitMethodInvocation(invocation, unused);
                }

                @Override
                public Void visitNewClass(NewClassTree newClass, Void unused) {
                    var args = newClass.getArguments();
                    if (!args.isEmpty()) {
                        long newPos = positions.getStartPosition(root, newClass);
                        long firstArgPos = positions.getStartPosition(root, args.get(0));
                        if (newPos >= 0 && newPos != firstArgPos) {
                            processCall(trees.getElement(getCurrentPath()), args);
                        }
                    }
                    return super.visitNewClass(newClass, unused);
                }

                private void processCall(
                        javax.lang.model.element.Element element,
                        List<? extends ExpressionTree> args) {
                    if (args.isEmpty()) return;
                    if (!(element instanceof ExecutableElement method)) return;

                    var params = method.getParameters();
                    int limit = Math.min(params.size(), args.size());

                    for (int i = 0; i < limit; i++) {
                        var argStart = positions.getStartPosition(root, args.get(i));
                        if (argStart < rangeStart || argStart > rangeEnd) continue;

                        var paramName = params.get(i).getSimpleName().toString();
                        // Skip meaningless names from bytecode stripped of debug info
                        if (paramName.matches("arg\\d+")) continue;

                        int hintLine = (int) lineMap.getLineNumber(argStart) - 1;
                        int hintCol = (int) lineMap.getColumnNumber(argStart) - 1;
                        hints.add(new InlayHint(new Position(hintLine, hintCol), paramName + ":", 2, true));
                    }
                }
            }.scan(root, null);

            return hints;
        } catch (RuntimeException | AssertionError e) {
            LOG.fine(
                    String.format(
                            "[perf] inlay_hints_skip file=%s reason=%s message=%s",
                            file.getFileName(), e.getClass().getSimpleName(), e.getMessage()));
            return List.of();
        }
    }
}
