package org.javacs.completion;

import com.sun.source.tree.CompilationUnitTree;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class IndexSnapshot {
    public static final IndexSnapshot EMPTY = new IndexSnapshot(0L, TypeMemberIndex.EMPTY);

    public final long version;
    public final Map<String, TypeMemberIndex.TypeInfo> types;
    private final TypeMemberIndex index;

    public IndexSnapshot(long version, TypeMemberIndex index) {
        this.version = version;
        this.index = Objects.requireNonNull(index, "index");
        this.types = Collections.unmodifiableMap(this.index.types());
    }

    public int size() {
        return index.size();
    }

    public boolean isEmpty() {
        return types.isEmpty();
    }

    public List<TypeMemberIndex.Member> members(String qualifiedName, boolean staticContext) {
        return index.members(qualifiedName, staticContext);
    }

    public Optional<TypeMemberIndex.Member> member(String qualifiedName, String name, boolean staticContext) {
        return index.member(qualifiedName, name, staticContext);
    }

    public Optional<String> resolveTypeName(String typeName, CompilationUnitTree root) {
        return index.resolveTypeName(typeName, root);
    }

    TypeMemberIndex index() {
        return index;
    }
}
