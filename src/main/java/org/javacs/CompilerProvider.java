package org.javacs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.tools.JavaFileObject;

public interface CompilerProvider {
    Set<String> imports();

    List<String> publicTopLevelTypes();

    default Set<Path> classPathRoots() {
        return Set.of();
    }

    Iterable<Path> search(String query);

    Optional<JavaFileObject> findAnywhere(String className);

    Path findTypeDeclaration(String className);

    Path[] findTypeReferences(String className);

    default Path[] findTypeReferences(Collection<String> classNames) {
        var result = new LinkedHashSet<Path>();
        for (var className : classNames) {
            for (var file : findTypeReferences(className)) result.add(file);
        }
        return result.toArray(Path[]::new);
    }

    Path[] findMemberReferences(String className, String memberName);

    ParseTask parse(Path file);

    ParseTask parse(JavaFileObject file);

    CompileTask compile(Path... files);

    CompileTask compile(Collection<? extends JavaFileObject> sources);

    /**
     * Compile files for a workspace-wide scan (references, implementations, rename). The result is
     * consumed once, so a context created only for this scan must not displace the warm contexts of
     * the modules being edited.
     */
    default CompileTask compileScan(Path... files) {
        return compile(files);
    }

    default List<ParseTask> parseAll(Collection<Path> files) {
        var result = new ArrayList<ParseTask>(files.size());
        for (var file : files) {
            result.add(parse(file));
        }
        return result;
    }

    default boolean lombokPresentOnClasspath() {
        return false;
    }

    default Optional<Path> decompileClass(String qualifiedName) {
        return Optional.empty();
    }

    default Optional<byte[]> findClassFile(String qualifiedName) {
        return Optional.empty();
    }

    Path NOT_FOUND = Paths.get("");
}
