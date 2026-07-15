package org.javacs.markup;

import com.sun.source.tree.*;
import com.sun.source.util.TreeScanner;
import java.util.List;

/**
 * Detects missing return statements from the parse tree without requiring type attribution.
 *
 * <p>This supplements javac's flow analysis which skips methods with erroneous types (e.g. when
 * Lombok-generated symbols are unresolved). The check is purely structural: "do all execution
 * paths through a non-void method body end in a return or throw?"
 */
final class MissingReturnCheck {
    private MissingReturnCheck() {}

    /** Returns true if the method has a non-void return type but not all paths return. */
    static boolean isMissingReturn(MethodTree method) {
        if (method.getReturnType() == null) return false; // constructor
        if (isVoidReturn(method.getReturnType())) return false;
        if (method.getBody() == null) return false; // abstract
        return !alwaysCompletes(method.getBody());
    }

    private static boolean isVoidReturn(Tree returnType) {
        return "void".equals(returnType.toString());
    }

    /** Returns true if the statement always completes abruptly (return/throw/infinite loop). */
    private static boolean alwaysCompletes(StatementTree stmt) {
        if (stmt == null) return false;
        return switch (stmt) {
            case ReturnTree r -> true;
            case ThrowTree t -> true;
            case BlockTree block -> blockAlwaysCompletes(block.getStatements());
            case IfTree ifTree -> ifAlwaysCompletes(ifTree);
            case SwitchTree sw -> switchAlwaysCompletes(sw);
            case TryTree tryTree -> tryAlwaysCompletes(tryTree);
            case LabeledStatementTree labeled -> alwaysCompletes(labeled.getStatement());
            case WhileLoopTree whileLoop -> isLiteralTrue(whileLoop.getCondition())
                    && !containsBreak(whileLoop);
            case DoWhileLoopTree doWhile -> isLiteralTrue(doWhile.getCondition())
                    && !containsBreak(doWhile);
            case ForLoopTree forLoop -> forLoop.getCondition() == null
                    && !containsBreak(forLoop);
            case SynchronizedTree sync -> alwaysCompletes(sync.getBlock());
            default -> false;
        };
    }

    private static boolean blockAlwaysCompletes(List<? extends StatementTree> statements) {
        if (statements == null || statements.isEmpty()) return false;
        for (var stmt : statements) {
            if (alwaysCompletes(stmt)) return true;
        }
        return false;
    }

    private static boolean ifAlwaysCompletes(IfTree ifTree) {
        if (ifTree.getElseStatement() == null) return false;
        return alwaysCompletes(ifTree.getThenStatement())
                && alwaysCompletes(ifTree.getElseStatement());
    }

    private static boolean switchAlwaysCompletes(SwitchTree sw) {
        boolean hasDefault = false;
        for (var caseTree : sw.getCases()) {
            if (caseTree.getExpressions().isEmpty()) hasDefault = true;
            var stmts = caseTree.getStatements();
            if (stmts != null && !blockAlwaysCompletes(stmts)) return false;
        }
        return hasDefault;
    }

    private static boolean tryAlwaysCompletes(TryTree tryTree) {
        // If finally always completes, the whole try completes
        if (tryTree.getFinallyBlock() != null && alwaysCompletes(tryTree.getFinallyBlock())) {
            return true;
        }
        // Otherwise: try block must complete AND all catch blocks must complete
        if (!alwaysCompletes(tryTree.getBlock())) return false;
        for (var catchTree : tryTree.getCatches()) {
            if (!alwaysCompletes(catchTree.getBlock())) return false;
        }
        return true;
    }

    private static boolean isLiteralTrue(ExpressionTree expr) {
        if (expr instanceof ParenthesizedTree p) return isLiteralTrue(p.getExpression());
        return expr instanceof LiteralTree lit && Boolean.TRUE.equals(lit.getValue());
    }

    private static boolean containsBreak(Tree loopTree) {
        var found = new boolean[]{false};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitBreak(BreakTree node, Void unused) {
                found[0] = true;
                return null;
            }
            // Don't descend into nested loops/switches — their breaks don't affect us
            @Override public Void visitWhileLoop(WhileLoopTree node, Void unused) { return null; }
            @Override public Void visitDoWhileLoop(DoWhileLoopTree node, Void unused) { return null; }
            @Override public Void visitForLoop(ForLoopTree node, Void unused) { return null; }
            @Override public Void visitSwitch(SwitchTree node, Void unused) { return null; }
        }.scan(loopTree, null);
        return found[0];
    }
}
