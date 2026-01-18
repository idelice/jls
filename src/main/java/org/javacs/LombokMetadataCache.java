package org.javacs;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Cache for Lombok metadata from the current compilation roots.
 *
 * <p>When diagnostic errors reference Lombok-generated members, this cache:
 * 1. Searches the current compilation roots for the target class
 * 2. Analyzes Lombok annotations using LombokSupport.analyze()
 * 3. Caches the result with timestamp for invalidation
 *
 * <p>Referenced classes outside the current compilation roots are parsed (not compiled) on demand
 * when their source is available in the workspace, avoiding compiler borrow conflicts.
 */
public class LombokMetadataCache {
    private static final Logger LOG = Logger.getLogger("main");

    /**
     * Cache entry holding metadata and creation time for invalidation.
     */
    private static class CacheEntry {
        final LombokMetadata metadata;
        final Instant created;

        CacheEntry(LombokMetadata metadata) {
            this.metadata = metadata;
            this.created = Instant.now();
        }
    }

    // Cache: className → (metadata, timestamp)
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final CompilerProvider compiler;

    public LombokMetadataCache(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    /**
     * Get Lombok metadata for a class from the given compilation roots.
     *
     * <p>This method only analyzes classes that are already in the compilation roots. It does NOT
     * compile referenced classes on-demand, as this would conflict with the main compilation task
     * that may already be borrowing the compiler.
     *
     * @param className Fully qualified class name (e.g., "com.example.models.Foo")
     * @param roots Compilation roots to search for the class
     * @return LombokMetadata if class is found and has Lombok annotations, null otherwise
     */
    public LombokMetadata get(String className, List<CompilationUnitTree> roots) {
        // Check if we have a fresh cached entry
        var entry = cache.get(className);
        if (entry != null && !needsReload(className, entry)) {
            return entry.metadata;
        }

        // Prefer classes already in the current compilation roots
        var classTree = findClassInRoots(roots, className);
        if (classTree == null) {
            // Fall back to parsing the source file if it exists in the workspace.
            // Parsing avoids compiler borrow conflicts while still letting us read Lombok annotations.
            var sourceFile = compiler.findTypeDeclaration(className);
            if (sourceFile == null || sourceFile.equals(CompilerProvider.NOT_FOUND)) {
                return null; // Class not found in workspace
            }
            var parse = compiler.parse(sourceFile);
            classTree = findClassInRoots(List.of(parse.root), className);
            if (classTree == null) {
                return null; // Class not found in parsed source
            }
        }

        // Analyze Lombok annotations
        var metadata = LombokSupport.analyze(classTree);

        // Cache the result only if it has Lombok annotations
        if (LombokSupport.hasLombokAnnotations(metadata)) {
            cache.put(className, new CacheEntry(metadata));
            return metadata;
        }

        cache.remove(className);
        return null; // No Lombok annotations
    }

    public LombokMetadata getFromSource(String className) {
        var sourceFile = compiler.findTypeDeclaration(className);
        if (sourceFile == null || sourceFile.equals(CompilerProvider.NOT_FOUND)) {
            cache.remove(className);
            return null;
        }
        var parse = compiler.parse(sourceFile);
        var classTree = findClassInRoots(List.of(parse.root), className);
        if (classTree == null) {
            cache.remove(className);
            return null;
        }
        var metadata = LombokSupport.analyze(classTree);
        if (LombokSupport.hasLombokAnnotations(metadata)) {
            cache.put(className, new CacheEntry(metadata));
            return metadata;
        }
        cache.remove(className);
        return null;
    }

    /**
     * Check if cache entry needs to be reloaded.
     *
     * <p>Follows Cache.java pattern: compares entry creation time vs file modification time using
     * FileStore.modified().
     *
     * @param className The class to check
     * @param entry The cached entry
     * @return true if cache is stale and should be recompiled, false if fresh
     */
    private boolean needsReload(String className, CacheEntry entry) {
        var sourceFile = compiler.findTypeDeclaration(className);
        if (sourceFile == null) {
            return true; // Source no longer exists
        }

        var fileModified = FileStore.modified(sourceFile);
        if (fileModified == null) {
            return true; // Can't determine modification time
        }

        // Reload if file was modified after cache entry was created
        return entry.created.isBefore(fileModified);
    }

    /**
     * Find ClassTree in compilation roots by fully qualified name.
     *
     * <p>Extracts package and simple class name from qualified name, then searches compilation
     * roots for matching class.
     *
     * @param roots Compilation roots to search
     * @param qualifiedName Fully qualified class name (e.g., "com.example.Foo")
     * @return ClassTree if found, null otherwise
     */
    private ClassTree findClassInRoots(List<CompilationUnitTree> roots, String qualifiedName) {
        // Extract package and simple class name
        var lastDot = qualifiedName.lastIndexOf('.');
        var packageName = lastDot > 0 ? qualifiedName.substring(0, lastDot) : "";
        var simpleClassName = qualifiedName.substring(lastDot + 1);

        for (var root : roots) {
            // Check package matches
            var rootPackage = root.getPackage();
            var rootPackageName = rootPackage != null ? rootPackage.getPackageName().toString() : "";
            if (!rootPackageName.equals(packageName)) {
                continue;
            }

            // Find class in this compilation unit
            for (var typeDecl : root.getTypeDecls()) {
                if (typeDecl.getKind() == Tree.Kind.CLASS) {
                    var classTree = (ClassTree) typeDecl;
                    if (classTree.getSimpleName().toString().equals(simpleClassName)) {
                        return classTree;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Clear cache for a specific class.
     *
     * <p>Called proactively when a file is modified or deleted to avoid stale entries.
     *
     * @param className The class to invalidate
     */
    public void invalidate(String className) {
        cache.remove(className);
    }

    /**
     * Clear entire cache.
     *
     * <p>Called when compiler is recreated or project settings change.
     */
    public void clear() {
        cache.clear();
    }
}
