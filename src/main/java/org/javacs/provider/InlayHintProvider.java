package org.javacs.provider;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.javacs.CompilerProvider;
import org.javacs.ParseTask;
import org.javacs.index.TypeIndexRouter;
import org.javacs.index.IndexedMember;
import org.javacs.resolve.ParseTypeResolver;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;

/**
 * Provides parameter-name inlay hints using the parse tree + workspace/external index.
 *
 * <p>Does NOT compile the file — resolves method calls via {@link ParseTypeResolver} and looks up
 * parameter names from {@link IndexedMember#parameterNames}. This makes it O(parse) instead of
 * O(compile), avoiding the 5-second javac attribution cost on large projects.
 */
public class InlayHintProvider {
    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;
    private final TypeIndexRouter typeIndex;

    public InlayHintProvider(CompilerProvider compiler, TypeIndexRouter typeIndex) {
        this.compiler = compiler;
        this.typeIndex = typeIndex;
    }

    public List<InlayHint> inlayHints(Path file, Range range) {
        try {
            return inlayHintsFromIndex(file, range);
        } catch (RuntimeException | AssertionError e) {
            LOG.fine(String.format(
                    "[perf] inlay_hints_skip file=%s reason=%s message=%s",
                    file.getFileName(), e.getClass().getSimpleName(), e.getMessage()));
            return List.of();
        }
    }

    private List<InlayHint> inlayHintsFromIndex(Path file, Range range) {
        var parseTask = compiler.parse(file);
        var root = parseTask.root();
        var lineMap = root.getLineMap();
        var positions = Trees.instance(parseTask.task()).getSourcePositions();
        var cursor = lineMap.getPosition(range.start.line + 1, range.start.character + 1);
        var typeIndex = this.typeIndex;

        // ParseTypeResolver for resolving receiver types
        var resolver = new ParseTypeResolver(parseTask, compiler, typeIndex, cursor);

        long fileLen;
        try {
            fileLen = root.getSourceFile().getCharContent(false).length();
        } catch (IOException e) {
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
                    if (invPos >= 0 && invPos != firstArgPos
                            && firstArgPos >= rangeStart && firstArgPos <= rangeEnd) {
                        processInvocation(invocation, args);
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
                    if (newPos >= 0 && newPos != firstArgPos
                            && firstArgPos >= rangeStart && firstArgPos <= rangeEnd) {
                        processConstructor(newClass, args);
                    }
                }
                return super.visitNewClass(newClass, unused);
            }

            private void processInvocation(MethodInvocationTree invocation, List<? extends ExpressionTree> args) {
                var select = invocation.getMethodSelect();
                String methodName;
                String ownerType = null;

                if (select instanceof IdentifierTree id) {
                    methodName = id.getName().toString();
                    var thisType = resolver.currentEnclosingTypeName();
                    if (thisType.isPresent()) ownerType = thisType.get();
                } else if (select instanceof MemberSelectTree ms) {
                    methodName = ms.getIdentifier().toString();
                    var receiverType = resolver.resolveExpression(ms.getExpression());
                    if (receiverType.isPresent()) ownerType = receiverType.get().qualifiedType();
                } else {
                    return;
                }

                if (ownerType == null || methodName == null) return;
                var member = findMember(ownerType, methodName, args.size());
                if (member == null || member.parameterNames == null) return;
                emitHints(member.parameterNames, args);
            }

            private void processConstructor(NewClassTree newClass, List<? extends ExpressionTree> args) {
                var typeTree = newClass.getIdentifier();
                var resolved = resolver.resolveTypeTree(typeTree, false);
                if (resolved.isEmpty()) return;
                var ownerType = resolved.get().qualifiedType();

                // Find constructor with matching arity
                for (var member : typeIndex.members(ownerType, false)) {
                    if (member.kind == CompletionItemKind.Constructor
                            && member.parameterNames != null
                            && member.parameterNames.length == args.size()) {
                        emitHints(member.parameterNames, args);
                        return;
                    }
                }
            }

            private IndexedMember findMember(String ownerType, String methodName, int argCount) {
                for (var member : typeIndex.members(ownerType, false)) {
                    if (member.kind == CompletionItemKind.Method
                            && methodName.equals(member.name)
                            && member.parameterNames != null
                            && member.parameterNames.length == argCount) {
                        return member;
                    }
                }
                // Try static
                for (var member : typeIndex.members(ownerType, true)) {
                    if (member.kind == CompletionItemKind.Method
                            && methodName.equals(member.name)
                            && member.parameterNames != null
                            && member.parameterNames.length == argCount) {
                        return member;
                    }
                }
                return null;
            }

            private void emitHints(String[] paramNames, List<? extends ExpressionTree> args) {
                int limit = Math.min(paramNames.length, args.size());
                for (int i = 0; i < limit; i++) {
                    var paramName = paramNames[i];
                    if (paramName == null || paramName.matches("arg\\d+")) continue;
                    var argStart = positions.getStartPosition(root, args.get(i));
                    if (argStart < rangeStart || argStart > rangeEnd) continue;
                    int hintLine = (int) lineMap.getLineNumber(argStart) - 1;
                    int hintCol = (int) lineMap.getColumnNumber(argStart) - 1;
                    hints.add(new InlayHint(new Position(hintLine, hintCol), paramName + ":", 2, true));
                }
            }
        }.scan(root, null);

        return hints;
    }
}
