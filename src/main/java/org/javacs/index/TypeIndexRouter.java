package org.javacs.index;

import com.sun.source.tree.CompilationUnitTree;
import java.nio.file.Path;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.Location;

import static org.javacs.index.TypeIndexRouter.OwnerStore.*;

/**
 * Read-only view over the published completion/navigation indexes.
 *
 * <p>The workspace snapshot is authoritative for workspace-owned symbols. The external index is a
 * dependency fallback for classpath types that do not belong to the workspace. Callers that
 * already know an owner is workspace-owned must stay on the workspace index instead of using this
 * facade as a fallback chooser.
 */
public record TypeIndexRouter(WorkspaceTypeIndex workspace, ExternalBinaryTypeIndex external) {
    public enum OwnerStore {
        WORKSPACE,
        EXTERNAL,
        NONE
    }

    public static final TypeIndexRouter EMPTY =
            new TypeIndexRouter(WorkspaceTypeIndex.EMPTY, ExternalBinaryTypeIndex.EMPTY);

    public TypeIndexRouter(WorkspaceTypeIndex workspace, ExternalBinaryTypeIndex external) {
        this.workspace = workspace == null ? WorkspaceTypeIndex.EMPTY : workspace;
        this.external = external == null ? ExternalBinaryTypeIndex.EMPTY : external;
    }

    public List<IndexedMember> members(String qualifiedName, boolean staticContext) {
        var workspaceMembers = workspace.members(qualifiedName, staticContext);
        if (!workspaceMembers.isEmpty() || isWorkspaceOwnedType(qualifiedName)) {
            return workspaceMembers;
        }
        return external.members(qualifiedName, staticContext);
    }

    public Optional<IndexedMember> member(String qualifiedName, String name, boolean staticContext) {
        var workspaceMember = workspace.member(qualifiedName, name, staticContext);
        if (workspaceMember.isPresent() || isWorkspaceOwnedType(qualifiedName)) {
            return workspaceMember;
        }
        return external.member(qualifiedName, name, staticContext);
    }

    public Optional<IndexedMember> member(
            String qualifiedName, String name, boolean staticContext, String[] erasedParameterTypes) {
        var workspaceMember = workspace.member(qualifiedName, name, staticContext, erasedParameterTypes);
        if (workspaceMember.isPresent() || isWorkspaceOwnedType(qualifiedName)) {
            return workspaceMember;
        }
        return external.member(qualifiedName, name, staticContext, erasedParameterTypes);
    }

    public Optional<String> resolveTypeName(String typeName, CompilationUnitTree root) {
        var workspaceName = workspace.resolveTypeName(typeName, root);
        if (workspaceName.isPresent()) {
            return workspaceName;
        }
        return external.resolveTypeName(typeName, root);
    }

    public Optional<IndexedType> resolveType(String typeName, CompilationUnitTree root) {
        var workspaceType = workspace.resolveType(typeName, root);
        if (workspaceType.isPresent()) {
            return workspaceType;
        }

        if (looksLikeWorkspaceMemberAccess(typeName, root)) {
            return Optional.empty();
        }
        return external.resolveType(typeName, root);
    }

    private boolean looksLikeWorkspaceMemberAccess(String typeName, CompilationUnitTree root) {
        if (typeName == null || root == null) {
            return false;
        }
        var split = typeName.lastIndexOf('.');
        if (split <= 0 || split == typeName.length() - 1) {
            return false;
        }
        var owner = typeName.substring(0, split);
        return workspace.resolveType(owner, root).isPresent();
    }

    public boolean containsType(String qualifiedName) {
        return workspace.containsType(qualifiedName) || external.containsType(qualifiedName);
    }

    /**
     * Returns whether the requested type belongs to the workspace, including nested candidates
     * under workspace owners such as {@code ServiceTwo.MyEnum}.
     *
     * <p>Interactive callers that already know the owner type should use this to stay on
     * {@link #workspace()} and avoid leaking workspace-owned symbols into dependency fallback.
     */
    public boolean isWorkspaceOwnedType(String qualifiedName) {
        return workspace.ownsTypeOrEnclosingType(qualifiedName);
    }

    public OwnerStore ownerStore(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return NONE;
        }
        if (isWorkspaceOwnedType(qualifiedName)) {
            return WORKSPACE;
        }
        return external.containsType(qualifiedName) ? EXTERNAL : NONE;
    }

    public Optional<IndexedType> ownerTypeInfo(String qualifiedType) {
        return switch (ownerStore(qualifiedType)) {
            case WORKSPACE -> workspace().typeInfo(qualifiedType);
            case EXTERNAL -> external().typeInfo(qualifiedType);
            case NONE -> Optional.empty();
        };
    }

    public Optional<IndexedType> typeInfo(String qualifiedName) {
        var workspaceType = workspace.typeInfo(qualifiedName);
        if (workspaceType.isPresent() || isWorkspaceOwnedType(qualifiedName)) {
            return workspaceType;
        }
        return external.typeInfo(qualifiedName);
    }

    public Set<String> workspaceSubTypes(String qualifiedName) {
        return workspace.subtypes(qualifiedName);
    }

    public Set<String> directSupertypes(String qualifiedName) {
        var workspaceSupertypes = workspace.directSupertypes(qualifiedName);
        if (!workspaceSupertypes.isEmpty()) {
            return workspaceSupertypes;
        }
        return typeInfo(qualifiedName)
                .map(type -> type.directSupertypes.isEmpty() ? Set.<String>of() : Set.copyOf(type.directSupertypes))
                .orElse(Set.of());
    }

    public Set<String> relatedMethodKeys(String ownerType, String memberName, String[] erasedParameterTypes) {
        var keys = new LinkedHashSet<>(workspace.relatedMethodKeys(ownerType, memberName, erasedParameterTypes));
        external.member(ownerType, memberName, false, erasedParameterTypes).ifPresent(member -> keys.add(member.canonicalKey));
        external.member(ownerType, memberName, true, erasedParameterTypes).ifPresent(member -> keys.add(member.canonicalKey));
        return Set.copyOf(keys);
    }

    public Optional<Path> externalDecompiledSourcePath(String qualifiedName) {
        return external.decompiledSourcePath(qualifiedName);
    }

    public Optional<String> workspaceNestedType(String ownerType, String simpleName) {
        if (ownerType == null || ownerType.isBlank() || simpleName == null || simpleName.isBlank()) {
            return Optional.empty();
        }
        var candidate = ownerType + "." + simpleName;
        return workspace.containsType(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    public Optional<IndexedMember> ownerMember(String ownerType, String name, boolean staticContext) {
        return switch (ownerStore(ownerType)) {
            case WORKSPACE -> workspace().member(ownerType, name, staticContext);
            case EXTERNAL -> external().member(ownerType, name, staticContext);
            case NONE -> Optional.empty();
        };
    }

    public Optional<IndexedMember> ownerMember(
            String ownerType, String name, boolean staticContext, String[] erasedParameterTypes) {
        return switch (ownerStore(ownerType)) {
            case WORKSPACE -> workspace().member(ownerType, name, staticContext, erasedParameterTypes);
            case EXTERNAL -> external().member(ownerType, name, staticContext, erasedParameterTypes);
            case NONE -> Optional.empty();
        };
    }

    public List<IndexedMember> ownerMembers(String ownerType, boolean staticContext) {
        return switch (ownerStore(ownerType)) {
            case WORKSPACE -> workspace().members(ownerType, staticContext);
            case EXTERNAL -> external().members(ownerType, staticContext);
            case NONE -> List.of();
        };

    }

    /** Return all overloads of a named method (instance or static) from the correct store. */
    public List<IndexedMember> methodOverloads(
            String ownerType, String methodName, boolean staticContext) {
        return ownerMembers(ownerType, staticContext).stream()
                .filter(m -> m.name.equals(methodName)
                        && (m.kind == CompletionItemKind.Method || m.kind == CompletionItemKind.Constructor))
                .toList();
    }

    /** Return constructor overloads for a type from the correct store. */
    public List<IndexedMember> constructors(String qualifiedName) {
        return switch (ownerStore(qualifiedName)) {
            case WORKSPACE -> workspace().constructors(qualifiedName);
            case EXTERNAL -> external().constructors(qualifiedName);
            case NONE -> List.of();
        };
    }

    /**
     * Returns the declaration {@link Location} for a member, routing through the correct store.
     *
     * <p>For members where {@code targetDeclarationKey} differs from {@code canonicalKey} (i.e.
     * synthetic members such as Lombok accessors and builder setters), the linked declaration is
     * looked up by key on the store that owns {@code declarationOwnerType}. For directly declared
     * members the member's own {@code declarationLocation()} is returned.
     */
    public Optional<Location> memberDeclarationLocation(IndexedMember member) {
        if (member == null) {
            return Optional.empty();
        }
        var targetKey = member.targetDeclarationKey;
        if (targetKey != null && !targetKey.equals(member.canonicalKey)) {
            return switch (ownerStore(member.declarationOwnerType)) {
                case WORKSPACE -> workspace.memberByCanonicalKey(targetKey)
                        .flatMap(IndexedMember::declarationLocation);
                case EXTERNAL, NONE -> Optional.empty();
            };
        }
        return member.declarationLocation();
    }
}
