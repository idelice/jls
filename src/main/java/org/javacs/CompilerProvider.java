package org.javacs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import javax.tools.JavaFileObject;
import org.javacs.SourceFileObject;

public interface CompilerProvider {
    Set<String> imports();

    List<String> publicTopLevelTypes();

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

    /** Compile a candidate set optimized for navigation. */
    default CompileTask compileForNavigation(Collection<? extends JavaFileObject> sources) {
        return compile(sources);
    }

    /** Compile a candidate set optimized for navigation, with a hint about the active file. */
    default CompileTask compileForNavigation(Path activeFile, Collection<? extends JavaFileObject> sources) {
        return compileForNavigation(sources);
    }

    /** Compile file paths optimized for navigation. */
    default CompileTask compileForNavigation(Path... files) {
        return compileForNavigation(null, toSourceFiles(files));
    }

    /** Compile file paths optimized for navigation, with a hint about the active file. */
    default CompileTask compileForNavigation(Path activeFile, Path... files) {
        return compileForNavigation(activeFile, toSourceFiles(files));
    }

    /** Compile a candidate set, pruning non-active files by default. */
    default CompileTask compileCandidates(Path activeFile, Path[] candidates) {
        return compileCandidates(activeFile, activeFile, candidates);
    }

    /** Compile a candidate set with separate pruning and Lombok hints. */
    default CompileTask compileCandidates(Path pruneActiveFile, Path lombokHintFile, Path[] candidates) {
        if (candidates == null || candidates.length == 0) {
            throw new RuntimeException("empty sources");
        }
        var sources = new java.util.ArrayList<JavaFileObject>();
        for (var candidate : candidates) {
            boolean pruned = pruneActiveFile != null && !candidate.equals(pruneActiveFile);
            sources.add(new SourceFileObject(candidate, pruned));
        }
        return compileForNavigation(lombokHintFile, sources);
    }

    private static List<JavaFileObject> toSourceFiles(Path... files) {
        var sources = new java.util.ArrayList<JavaFileObject>();
        for (var f : files) {
            sources.add(new SourceFileObject(f));
        }
        return sources;
    }

    /** Run on a pruned candidate batch, with optional full-compile fallback. */
    default <T> T runCandidatesWithFallback(
            Path activeFile,
            Path[] candidates,
            Function<CompileTask, T> action,
            BiPredicate<T, Path[]> shouldFallback) {
        return runCandidatesWithFallback(activeFile, activeFile, candidates, action, shouldFallback);
    }

    /** Run on a pruned candidate batch with Lombok hint, with optional full-compile fallback. */
    default <T> T runCandidatesWithFallback(
            Path pruneActiveFile,
            Path lombokHintFile,
            Path[] candidates,
            Function<CompileTask, T> action,
            BiPredicate<T, Path[]> shouldFallback) {
        try (var task = compileCandidates(pruneActiveFile, lombokHintFile, candidates)) {
            var result = action.apply(task);
            if (shouldFallback == null || !shouldFallback.test(result, candidates)) {
                return result;
            }
        }
        try (var task = compile(candidates)) {
            return action.apply(task);
        }
    }

    /** Lightweight compile for completion; default delegates to full compile. */
    default CompileTask compileForCompletion(Collection<? extends JavaFileObject> sources) {
        return compile(sources);
    }

    Path NOT_FOUND = Paths.get("");
}
