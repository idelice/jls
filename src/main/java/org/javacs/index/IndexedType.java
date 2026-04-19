package org.javacs.index;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Modifier;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.Location;
import org.javacs.lsp.Range;

/**
 * Shared immutable indexed type snapshot published by both workspace and external indexes.
 *
 * <p>The indexes remain separate stores. {@link IndexedMember.Provenance} tells callers which
 * store produced the current snapshot.
 */
public final class IndexedType {
    public final String qualifiedName;
    public final String simpleName;
    public final List<IndexedMember> members;
    public final boolean fromCompiledRoot;
    public final Path sourcePath;
    public final URI sourceUri;
    public final String superclass;
    public final List<String> interfaces;
    public final List<String> directSupertypes;
    public final List<String> nestedTypes;
    public final String packageName;
    public final List<String> enclosingTypes;
    public final int kind;
    public final Set<Modifier> modifiers;
    public final Range declarationRange;
    public final IndexedMember.Provenance provenance;

    public IndexedType(
            String qualifiedName,
            String simpleName,
            List<IndexedMember> members,
            boolean fromCompiledRoot,
            Path sourcePath,
            String superclass,
            List<String> interfaces,
            IndexedMember.Provenance provenance) {
        this(
                qualifiedName,
                simpleName,
                members,
                fromCompiledRoot,
                sourcePath,
                sourcePath == null ? null : sourcePath.toUri(),
                superclass,
                interfaces,
                List.of(),
                CompletionItemKind.Class,
                Set.of(),
                null,
                provenance);
    }

    public IndexedType(
            String qualifiedName,
            String simpleName,
            List<IndexedMember> members,
            boolean fromCompiledRoot,
            Path sourcePath,
            URI sourceUri,
            String superclass,
            List<String> interfaces,
            List<String> nestedTypes,
            int kind,
            Set<Modifier> modifiers,
            Range declarationRange,
            IndexedMember.Provenance provenance) {
        this.qualifiedName = qualifiedName;
        this.simpleName = simpleName;
        this.members = Collections.unmodifiableList(new ArrayList<>(members));
        this.fromCompiledRoot = fromCompiledRoot;
        this.sourcePath = sourcePath;
        this.sourceUri = sourceUri;
        this.superclass = superclass;
        this.interfaces = Collections.unmodifiableList(new ArrayList<>(interfaces));
        this.directSupertypes =
                Collections.unmodifiableList(new ArrayList<>(buildDirectSupertypes(superclass, interfaces)));
        this.nestedTypes = Collections.unmodifiableList(new ArrayList<>(nestedTypes));
        this.packageName = packageOf(qualifiedName);
        this.enclosingTypes = Collections.unmodifiableList(new ArrayList<>(enclosingTypesOf(qualifiedName)));
        this.kind = kind;
        this.modifiers = modifiers == null ? Set.of() : Set.copyOf(modifiers);
        this.declarationRange = declarationRange;
        this.provenance =
                provenance == null ? IndexedMember.Provenance.WORKSPACE : provenance;
    }

    public Optional<Location> declarationLocation() {
        if (sourceUri == null || declarationRange == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(sourceUri, declarationRange));
    }

    private static List<String> buildDirectSupertypes(String superclass, List<String> interfaces) {
        var direct = new ArrayList<String>();
        if (superclass != null && !superclass.isBlank()) {
            direct.add(superclass);
        }
        if (interfaces != null) {
            for (var iface : interfaces) {
                if (iface != null && !iface.isBlank()) {
                    direct.add(iface);
                }
            }
        }
        return direct;
    }

    private static String packageOf(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return "";
        }
        var last = qualifiedName.lastIndexOf('.');
        if (last < 0) {
            return "";
        }
        return qualifiedName.substring(0, last);
    }

    private static List<String> enclosingTypesOf(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return List.of();
        }
        var parts = qualifiedName.split("\\.");
        if (parts.length < 3) {
            return List.of();
        }
        var packageName = packageOf(qualifiedName);
        var packageSegments = packageName.isBlank() ? 0 : packageName.split("\\.").length;
        if (parts.length - packageSegments <= 1) {
            return List.of();
        }
        var enclosing = new ArrayList<String>();
        var current = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (current.length() > 0) {
                current.append('.');
            }
            current.append(parts[i]);
            if (i >= packageSegments) {
                enclosing.add(current.toString());
            }
        }
        return enclosing;
    }
}
