package org.javacs;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;

import javax.tools.JavaFileObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Helper class for compiling small Java snippets during tests.
 * Provides a simple way to compile source code and get the ClassTree for testing.
 */
public class TestCompiler {
    private static final Path TEST_CLASS_PATH = Paths.get("target/test-classes");
    private static final CompilerProvider provider = LanguageServerFixture.getCompilerProvider();
    private static int uniqueCounter = 0;

    /**
     * Compile a Java source snippet and return the first public class ClassTree.
     *
     * @param source Java source code
     * @return ClassTree of the first public class, or null if not found
     */
    public ClassTree compile(String source) {
        try {
            // Create unique class name to avoid FileStore caching issues
            var uniqueClassName = "Test_" + System.nanoTime() + "_" + (uniqueCounter++);
            var modifiedSource = source.replaceFirst("public\\s+class\\s+\\w+", "public class " + uniqueClassName);

            var path = TEST_CLASS_PATH.resolve(uniqueClassName + ".java");

            // Ensure directory exists
            Files.createDirectories(path.getParent());
            Files.write(path, modifiedSource.getBytes());

            // Clear FileStore to ensure fresh compilation
            FileStore.reset();

            // Compile using CompileTask and extract results
            var task = provider.compile(path);
            var result = extractFirstClass(task.roots);

            // Properly close the task to release compiler borrow
            task.close();

            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile: " + e.getMessage(), e);
        }
    }

    private ClassTree extractFirstClass(Iterable<? extends CompilationUnitTree> roots) {
        for (var root : roots) {
            for (var typeDecl : root.getTypeDecls()) {
                if (typeDecl.getKind() == Tree.Kind.CLASS) {
                    return (ClassTree) typeDecl;
                }
            }
        }
        return null;
    }
}
