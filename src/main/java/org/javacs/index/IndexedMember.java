package org.javacs.index;

import java.io.Serializable;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Modifier;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.Location;
import org.javacs.lsp.Range;

/**
 * Shared immutable indexed member snapshot published by both workspace and external indexes.
 *
 * <p>The indexes remain separate stores. {@link Provenance} tells callers which store produced
 * the current snapshot.
 */
public final class IndexedMember implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Comparator<IndexedMember> ORDER =
            Comparator.comparingInt((IndexedMember member) -> member.priority)
                    .thenComparing(member -> member.name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(member -> member.detail);

    public enum Origin {
        DECLARED,
        RECORD_COMPONENT,
        LOMBOK_ACCESSOR,
        LOMBOK_BUILDER,
        LOMBOK_LOGGER,
        DECOMPILATION
    }

    public enum Provenance {
        WORKSPACE,
        EXTERNAL_BINARY
    }

    public final String ownerType;
    public final String name;
    public final int kind;
    public final boolean isStatic;
    public final boolean isPrivate;
    public final boolean isProtected;
    public final boolean isPublic;
    public final boolean isAbstract;
    public final int priority;
    public final String detail;
    public final String returnType;
    public final String declaredReturnType;
    public final String[] parameterNames;
    public final String[] erasedParameterTypes;
    public final String[] declaredParameterTypes;
    public final String canonicalKey;
    public final String logicalKey;
    public final String backingFieldName;
    public final boolean synthetic;
    public final Origin origin;
    public final Provenance provenance;
    public final Set<Modifier> modifiers;
    public final URI sourceUri;
    public final Range declarationRange;
    public final String declarationOwnerType;
    public final String targetDeclarationKey;

    public IndexedMember(
            String ownerType,
            String name,
            int kind,
            boolean isStatic,
            boolean isPrivate,
            int priority,
            String detail,
            String returnType,
            String[] parameterNames,
            String[] erasedParameterTypes,
            String canonicalKey,
            String logicalKey,
            String backingFieldName,
            boolean synthetic) {
        this(
                ownerType,
                name,
                kind,
                isStatic,
                isPrivate,
                priority,
                detail,
                returnType,
                parameterNames,
                erasedParameterTypes,
                canonicalKey,
                logicalKey,
                backingFieldName,
                synthetic,
                Provenance.WORKSPACE);
    }

    public IndexedMember(
            String ownerType,
            String name,
            int kind,
            boolean isStatic,
            boolean isPrivate,
            int priority,
            String detail,
            String returnType,
            String[] parameterNames,
            String[] erasedParameterTypes,
            String canonicalKey,
            String logicalKey,
            String backingFieldName,
            boolean synthetic,
            Provenance provenance) {
        this(
                ownerType,
                name,
                kind,
                isStatic,
                isPrivate,
                false,
                !isPrivate,
                false,
                priority,
                detail,
                returnType,
                returnType,
                parameterNames,
                erasedParameterTypes,
                erasedParameterTypes,
                canonicalKey,
                logicalKey,
                backingFieldName,
                synthetic,
                Origin.DECLARED,
                provenance,
                Set.of(),
                null,
                null,
                ownerType,
                canonicalKey);
    }

    public IndexedMember(
            String ownerType,
            String name,
            int kind,
            boolean isStatic,
            boolean isPrivate,
            boolean isProtected,
            boolean isPublic,
            boolean isAbstract,
            int priority,
            String detail,
            String returnType,
            String declaredReturnType,
            String[] parameterNames,
            String[] erasedParameterTypes,
            String[] declaredParameterTypes,
            String canonicalKey,
            String logicalKey,
            String backingFieldName,
            boolean synthetic,
            Origin origin,
            Set<Modifier> modifiers,
            URI sourceUri,
            Range declarationRange) {
        this(
                ownerType,
                name,
                kind,
                isStatic,
                isPrivate,
                isProtected,
                isPublic,
                isAbstract,
                priority,
                detail,
                returnType,
                declaredReturnType,
                parameterNames,
                erasedParameterTypes,
                declaredParameterTypes,
                canonicalKey,
                logicalKey,
                backingFieldName,
                synthetic,
                origin,
                Provenance.WORKSPACE,
                modifiers,
                sourceUri,
                declarationRange,
                ownerType,
                canonicalKey);
    }

    public IndexedMember(
            String ownerType,
            String name,
            int kind,
            boolean isStatic,
            boolean isPrivate,
            boolean isProtected,
            boolean isPublic,
            boolean isAbstract,
            int priority,
            String detail,
            String returnType,
            String declaredReturnType,
            String[] parameterNames,
            String[] erasedParameterTypes,
            String[] declaredParameterTypes,
            String canonicalKey,
            String logicalKey,
            String backingFieldName,
            boolean synthetic,
            Origin origin,
            Provenance provenance,
            Set<Modifier> modifiers,
            URI sourceUri,
            Range declarationRange,
            String declarationOwnerType,
            String targetDeclarationKey) {
        this.ownerType = ownerType;
        this.name = name;
        this.kind = kind;
        this.isStatic = isStatic;
        this.isPrivate = isPrivate;
        this.isProtected = isProtected;
        this.isPublic = isPublic;
        this.isAbstract = isAbstract;
        this.priority = priority;
        this.detail = detail;
        this.returnType = returnType;
        this.declaredReturnType = declaredReturnType;
        this.parameterNames = parameterNames;
        this.erasedParameterTypes = erasedParameterTypes;
        this.declaredParameterTypes = declaredParameterTypes;
        this.canonicalKey = canonicalKey;
        this.logicalKey = logicalKey;
        this.backingFieldName = backingFieldName;
        this.synthetic = synthetic;
        this.origin = origin == null ? Origin.DECLARED : origin;
        this.provenance = provenance == null ? Provenance.WORKSPACE : provenance;
        this.modifiers = modifiers == null ? Set.of() : Set.copyOf(modifiers);
        this.sourceUri = sourceUri;
        this.declarationRange = declarationRange;
        this.declarationOwnerType = declarationOwnerType != null ? declarationOwnerType : ownerType;
        this.targetDeclarationKey = targetDeclarationKey != null ? targetDeclarationKey : canonicalKey;
    }

    public IndexedMember withNavigation(String declarationOwnerType, String targetDeclarationKey) {
        return new IndexedMember(
                ownerType, name, kind, isStatic, isPrivate, isProtected, isPublic, isAbstract,
                priority, detail, returnType, declaredReturnType,
                parameterNames, erasedParameterTypes, declaredParameterTypes,
                canonicalKey, logicalKey, backingFieldName, synthetic,
                origin, provenance, modifiers, sourceUri, declarationRange,
                declarationOwnerType, targetDeclarationKey);
    }

    public IndexedMember withDeclaredTypes(String newDeclaredReturnType, String[] newDeclaredParameterTypes) {
        return new IndexedMember(
                ownerType, name, kind, isStatic, isPrivate, isProtected, isPublic, isAbstract,
                priority, detail, returnType, newDeclaredReturnType,
                parameterNames, erasedParameterTypes, newDeclaredParameterTypes,
                canonicalKey, logicalKey, backingFieldName, synthetic,
                origin, provenance, modifiers, sourceUri, declarationRange,
                declarationOwnerType, targetDeclarationKey);
    }

    public static String canonicalKey(
            String ownerType, int kind, String name, String[] erasedParameterTypes) {
        if (kind == CompletionItemKind.Method || kind == CompletionItemKind.Constructor) {
            var params = erasedParameterTypes == null ? new String[0] : erasedParameterTypes;
            return ownerType + "#" + name + "(" + String.join(",", params) + ")";
        }
        return ownerType + "#" + name;
    }

    public static void sort(List<IndexedMember> members) {
        members.sort(ORDER);
    }

    public Optional<Location> declarationLocation() {
        if (sourceUri == null || declarationRange == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(sourceUri, declarationRange));
    }
}
