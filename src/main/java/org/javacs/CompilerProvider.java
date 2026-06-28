package org.javacs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
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

    List<String> packagePrivateTopLevelTypes(String packageName);

    Iterable<Path> search(String query);

    Optional<JavaFileObject> findAnywhere(String className);

    Path findTypeDeclaration(String className);

    Path[] findTypeReferences(String className);

    Path[] findMemberReferences(String className, String memberName);

    ParseTask parse(Path file);

    ParseTask parse(JavaFileObject file);

    CompileTask compile(Path... files);

    CompileTask compile(Collection<? extends JavaFileObject> sources);

    default CompileTask compileFresh(Path... files) {
        return compile(files);
    }

    default List<ParseTask> parseAll(Collection<Path> files) {
        var result = new java.util.ArrayList<ParseTask>(files.size());
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

    default Optional<Path> findClassFile(String qualifiedName) {
        return Optional.empty();
    }

    Path NOT_FOUND = Paths.get("");
}
