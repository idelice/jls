package org.javacs.navigation;

import java.util.Optional;
import org.javacs.index.IndexedMember;
import org.javacs.lsp.Location;

/**
 * Lightweight symbol identity extracted from the cursor position.
 *
 * <p>Carries just enough information for reference scanning: the owner type, member name, kind
 * flags, an optional index member, and the primary declaration location for exclusion filtering.
 * It does not carry the full source-materialization chain that {@code DefinitionProvider} uses for
 * display — callers that need decompiled or Lombok-navigated sources should use {@code
 * DefinitionProvider.ResolvedSymbol} directly.
 */
public record SymbolIdentity(
        String qualifiedType,
        String memberName,
        boolean method,
        IndexedMember indexMember,
        String simpleName,
        Optional<Location> declarationLocation) {

    public static SymbolIdentity unsupported(String simpleName) {
        return new SymbolIdentity(null, null, false, null, simpleName, Optional.empty());
    }
}
