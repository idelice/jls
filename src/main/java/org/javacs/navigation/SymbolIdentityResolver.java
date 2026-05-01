package org.javacs.navigation;

import com.sun.source.util.TreePath;
import org.javacs.ParseTask;

/**
 * Resolves symbol identity at a cursor position or at a candidate occurrence during a reference
 * scan.
 *
 * <p>{@link #resolveTarget()} is called once per reference request and may use an ATTR compile.
 * {@link #resolveOccurrence(ParseTask, TreePath)} is called for every candidate node during the
 * scan and must be parse-only.
 */
public interface SymbolIdentityResolver {

    /** Resolve the symbol at the cursor that initiated the request. */
    SymbolIdentity resolveTarget();

    /**
     * Resolve the identity of a candidate occurrence during a reference scan.
     *
     * <p>Must not trigger any new compilation.
     */
    SymbolIdentity resolveOccurrence(ParseTask parse, TreePath path);
}
