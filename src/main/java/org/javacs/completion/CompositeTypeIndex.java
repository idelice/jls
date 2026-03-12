package org.javacs.completion;

import com.sun.source.tree.CompilationUnitTree;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.javacs.CompilerProvider;

/**
 * Read-only view over the published completion/navigation indexes.
 *
 * <p>The workspace snapshot is authoritative for workspace-owned symbols. The external index is a
 * dependency fallback for classpath types that do not belong to the workspace. Callers that
 * already know an owner is workspace-owned must stay on the workspace index instead of using this
 * facade as a fallback chooser.
 */
public final class CompositeTypeIndex {
    public static final CompositeTypeIndex EMPTY =
            new CompositeTypeIndex(WorkspaceTypeIndex.EMPTY, ExternalBinaryTypeIndex.EMPTY);

    private final WorkspaceTypeIndex workspace;
    private final ExternalBinaryTypeIndex external;

    public CompositeTypeIndex(WorkspaceTypeIndex workspace, ExternalBinaryTypeIndex external) {
        this.workspace = workspace == null ? WorkspaceTypeIndex.EMPTY : workspace;
        this.external = external == null ? ExternalBinaryTypeIndex.EMPTY : external;
    }

    public WorkspaceTypeIndex workspace() {
        return workspace;
    }

    public ExternalBinaryTypeIndex external() {
        return external;
    }

    public List<TypeMemberIndex.Member> members(String qualifiedName, boolean staticContext) {
        var workspaceMembers = workspace.members(qualifiedName, staticContext);
        if (!workspaceMembers.isEmpty()) {
            return workspaceMembers;
        }
        return external.members(qualifiedName, staticContext);
    }

    public Optional<TypeMemberIndex.Member> member(String qualifiedName, String name, boolean staticContext) {
        var workspaceMember = workspace.member(qualifiedName, name, staticContext);
        if (workspaceMember.isPresent()) {
            return workspaceMember;
        }
        return external.member(qualifiedName, name, staticContext);
    }

    public Optional<TypeMemberIndex.Member> member(
            String qualifiedName, String name, boolean staticContext, String[] erasedParameterTypes) {
        var workspaceMember = workspace.member(qualifiedName, name, staticContext, erasedParameterTypes);
        if (workspaceMember.isPresent()) {
            return workspaceMember;
        }
        return external.member(qualifiedName, name, staticContext, erasedParameterTypes);
    }

    public Optional<TypeMemberIndex.Member> memberByCanonicalKey(String canonicalKey) {
        var workspaceMember = workspace.memberByCanonicalKey(canonicalKey);
        if (workspaceMember.isPresent()) {
            return workspaceMember;
        }
        return external.memberByCanonicalKey(canonicalKey);
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
    public boolean isWorkspaceOwnedType(String qualifiedName, CompilerProvider compiler) {
        if (qualifiedName == null || qualifiedName.isBlank() || compiler == null) {
            return false;
        }
        if (workspace.containsType(qualifiedName)
                || compiler.findTypeDeclaration(qualifiedName) != CompilerProvider.NOT_FOUND) {
            return true;
        }
        for (var i = qualifiedName.lastIndexOf('.'); i > 0; i = qualifiedName.lastIndexOf('.', i - 1)) {
            var outer = qualifiedName.substring(0, i);
            if (workspace.containsType(outer)
                    || compiler.findTypeDeclaration(outer) != CompilerProvider.NOT_FOUND) {
                return true;
            }
        }
        return false;
    }

    public Optional<TypeMemberIndex.TypeInfo> typeInfo(String qualifiedName) {
        var workspaceType = workspace.typeInfo(qualifiedName);
        if (workspaceType.isPresent()) {
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
                .map(
                        info -> {
                            var result = new java.util.LinkedHashSet<String>();
                            if (info.superclass != null && !info.superclass.isBlank()) {
                                result.add(info.superclass);
                            }
                            result.addAll(info.interfaces);
                            return Set.copyOf(result);
                        })
                .orElse(Set.of());
    }

    public Set<String> relatedMethodKeys(String ownerType, String memberName, String[] erasedParameterTypes) {
        var keys = new java.util.LinkedHashSet<String>(workspace.relatedMethodKeys(ownerType, memberName, erasedParameterTypes));
        external.member(ownerType, memberName, false, erasedParameterTypes).ifPresent(member -> keys.add(member.canonicalKey));
        external.member(ownerType, memberName, true, erasedParameterTypes).ifPresent(member -> keys.add(member.canonicalKey));
        return Set.copyOf(keys);
    }

    public Optional<Path> externalDecompiledSourcePath(String qualifiedName) {
        return external.decompiledSourcePath(qualifiedName);
    }
}
