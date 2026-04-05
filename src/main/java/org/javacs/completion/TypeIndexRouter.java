package org.javacs.completion;

import com.sun.source.tree.CompilationUnitTree;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.javacs.index.IndexedMember;
import org.javacs.index.IndexedType;
import java.util.Set;

/**
 * Read-only view over the published completion/navigation indexes.
 *
 * <p>The workspace snapshot is authoritative for workspace-owned symbols. The external index is a
 * dependency fallback for classpath types that do not belong to the workspace. Callers that
 * already know an owner is workspace-owned must stay on the workspace index instead of using this
 * facade as a fallback chooser.
 */
public record TypeIndexRouter(WorkspaceTypeIndex workspace, ExternalBinaryTypeIndex external) {
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

    public Optional<IndexedType> typeInfo(String qualifiedName) {
        var workspaceType = workspace.typeInfo(qualifiedName);
        if (workspaceType.isPresent() || isWorkspaceOwnedType(qualifiedName)) {
            return workspaceType;
        }
        return external.typeInfo(qualifiedName);
    }

    public Set<String> subtypes(String qualifiedName) {
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
}
