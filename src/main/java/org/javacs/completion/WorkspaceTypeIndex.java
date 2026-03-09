package org.javacs.completion;

import com.sun.source.tree.CompilationUnitTree;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.javacs.CompileTask;

public final class WorkspaceTypeIndex {
    public static final WorkspaceTypeIndex EMPTY = new WorkspaceTypeIndex(TypeMemberIndex.EMPTY);

    private final TypeMemberIndex delegate;

    public WorkspaceTypeIndex(TypeMemberIndex delegate) {
        this.delegate = delegate == null ? TypeMemberIndex.EMPTY : delegate;
    }

    public static WorkspaceTypeIndex wrap(TypeMemberIndex delegate) {
        return new WorkspaceTypeIndex(delegate);
    }

    public static WorkspaceTypeIndex from(CompileTask task) {
        return new WorkspaceTypeIndex(TypeMemberIndex.from(task));
    }

    public static WorkspaceTypeIndex workspaceDeclarations(CompileTask task) {
        return new WorkspaceTypeIndex(TypeMemberIndex.workspaceDeclarations(task));
    }

    public TypeMemberIndex unwrap() {
        return delegate;
    }

    public int size() {
        return delegate.size();
    }

    public List<TypeMemberIndex.Member> members(String qualifiedName, boolean staticContext) {
        return delegate.members(qualifiedName, staticContext);
    }

    public Optional<TypeMemberIndex.Member> member(String qualifiedName, String name, boolean staticContext) {
        return delegate.member(qualifiedName, name, staticContext);
    }

    public Optional<String> resolveTypeName(String typeName, CompilationUnitTree root) {
        return delegate.resolveTypeName(typeName, root);
    }

    public boolean containsType(String qualifiedName) {
        return delegate.types().containsKey(qualifiedName);
    }

    public Optional<TypeMemberIndex.TypeInfo> typeInfo(String qualifiedName) {
        return Optional.ofNullable(delegate.types().get(qualifiedName));
    }

    public WorkspaceTypeIndex replaceWorkspaceDeclarations(WorkspaceTypeIndex updates, Set<Path> replacedFiles) {
        return new WorkspaceTypeIndex(delegate.replaceWorkspaceDeclarations(updates == null ? TypeMemberIndex.EMPTY : updates.delegate, replacedFiles));
    }
}
