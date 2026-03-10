package org.javacs.completion;

import com.sun.source.tree.CompilationUnitTree;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    public Optional<TypeMemberIndex.TypeInfo> typeInfo(String qualifiedName) {
        var workspaceType = workspace.typeInfo(qualifiedName);
        if (workspaceType.isPresent()) {
            return workspaceType;
        }
        return external.typeInfo(qualifiedName);
    }

    public Optional<Path> externalDecompiledSourcePath(String qualifiedName) {
        return external.decompiledSourcePath(qualifiedName);
    }
}
