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

    default CompileTask compileFast(Path... files) {
        return compile(files);
    }

    default CompileTask compileFast(Collection<? extends JavaFileObject> sources) {
        return compile(sources);
    }

    default CompileTask compileFastWithProcessors(Path... files) {
        return compileFast(files);
    }

    default CompileTask compileFastWithProcessors(Collection<? extends JavaFileObject> sources) {
        return compileFast(sources);
    }

    /** Per-file compile without workspace widening. For hover. */
    default CompileTask compilePerFile(Path file) {
        return compileFast(file);
    }

    /** Returns true when Lombok is present on the project classpath. */
    default boolean lombokPresentOnClasspath() {
        return false;
    }

    /**
     * Decompile the top-level source file that contains {@code qualifiedName} using the bundled
     * Vineflower decompiler and return the path to the generated {@code .java} file.
     *
     * <p>The result is cached on disk by fingerprint so repeated calls are cheap. Returns empty
     * when the type cannot be located on the classpath or decompilation fails.
     */
    default Optional<java.nio.file.Path> decompileClass(String qualifiedName) {
        return Optional.empty();
    }

    Path NOT_FOUND = Paths.get("");
}
