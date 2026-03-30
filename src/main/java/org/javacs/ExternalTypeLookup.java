package org.javacs;

import com.sun.source.tree.CompilationUnitTree;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.logging.Logger;
import javax.tools.JavaFileObject;
import org.javacs.completion.TypeIndexRouter;
import org.javacs.completion.WorkspaceTypeIndex;

/**
 * Resolves type names and type source across the workspace/dependency boundary.
 *
 * <p>Use this helper for type-name lookup and source opening. Member ownership stays with the
 * caller: if a request already knows it is resolving a workspace owner, it must stay on the
 * workspace path instead of relying on composite member lookup.
 */
public final class ExternalTypeLookup {
    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;
    private final TypeIndexRouter index;

    public ExternalTypeLookup(CompilerProvider compiler, TypeIndexRouter index) {
        this.compiler = compiler;
        this.index = index == null ? TypeIndexRouter.EMPTY : index;
    }

    /**
     * Resolve a type name using the strict feature boundary: workspace first, then dependency/JDK
     * lookup only after workspace candidates are exhausted.
     */
    public Optional<String> resolveTypeName(String typeName, CompilationUnitTree root) {
        var workspace = resolveWorkspaceType(typeName, root);
        if (workspace.isPresent()) {
            return workspace;
        }
        return resolveExternalDependencyType(typeName, root);
    }

    /**
     * Resolve only workspace-owned candidates. This phase must not call external dependency
     * lookup.
     */
    public Optional<String> resolveWorkspaceType(String typeName, CompilationUnitTree root) {
        if (typeName == null || typeName.isBlank() || root == null) {
            return Optional.empty();
        }
        var raw = normalizeTypeName(typeName);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        if (WorkspaceTypeIndex.isPrimitiveTypeName(raw)) {
            return Optional.of(raw);
        }
        var workspace = index.workspace().resolveTypeName(typeName, root);
        if (workspace.isPresent()) {
            return workspace;
        }
        if (raw.contains(".") && workspaceTypeExists(raw)) {
            return Optional.of(raw);
        }
        for (var candidate : scopedCandidates(raw, root)) {
            if (workspaceTypeExists(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Nested types under workspace owners stay in the workspace phase. They must never trigger an
     * external dependency probe.
     */
    public Optional<String> resolveWorkspaceNestedType(String ownerType, String simpleName) {
        if (ownerType == null || ownerType.isBlank() || simpleName == null || simpleName.isBlank()) {
            return Optional.empty();
        }
        var candidate = ownerType + "." + simpleName;
        if (workspaceTypeExists(candidate)) {
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /**
     * Workspace-owned candidates include direct workspace declarations and nested candidates under
     * workspace owners such as {@code ServiceTwo.MyEnum}.
     */
    private boolean isWorkspaceOwnedCandidate(String qualifiedType) {
        if (qualifiedType == null || qualifiedType.isBlank()) {
            return false;
        }
        if (workspaceTypeExists(qualifiedType)) {
            return true;
        }
        for (var i = qualifiedType.lastIndexOf('.'); i > 0; i = qualifiedType.lastIndexOf('.', i - 1)) {
            var outer = qualifiedType.substring(0, i);
            if (workspaceTypeExists(outer)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the workspace declaration path without consulting external dependency lookup. */
    public Path findWorkspaceDeclaration(String qualifiedType) {
        return compiler.findTypeDeclaration(qualifiedType);
    }

    /**
     * Opens dependency/JDK/doc-path source only after the workspace boundary has been checked.
     */
    public Optional<JavaFileObject> findExternalSource(String qualifiedType, String reason) {
        if (shouldBlockExternalLookup(qualifiedType, reason)) {
            return Optional.empty();
        }
        return compiler.findAnywhere(qualifiedType);
    }

    /**
     * Resolve only dependency/JDK candidates. Call this only after {@link #resolveWorkspaceType}
     * returned empty.
     */
    public Optional<String> resolveExternalDependencyType(String typeName, CompilationUnitTree root) {
        if (typeName == null || typeName.isBlank() || root == null) {
            return Optional.empty();
        }
        var raw = normalizeTypeName(typeName);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        if (WorkspaceTypeIndex.isPrimitiveTypeName(raw)) {
            return Optional.of(raw);
        }
        if (raw.contains(".") && resolveExternalQualifiedType(raw, "qualified").isPresent()) {
            return Optional.of(raw);
        }
        for (var candidate : scopedCandidates(raw, root)) {
            var resolved = resolveExternalQualifiedType(candidate, "scoped");
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        var javaLang = "java.lang." + raw;
        var javaLangResolved = resolveExternalQualifiedType(javaLang, "java.lang");
        if (javaLangResolved.isPresent()) {
            return javaLangResolved;
        }
        return Optional.empty();
    }

    private Optional<String> resolveExternalQualifiedType(String qualifiedType, String reason) {
        if (qualifiedType == null || qualifiedType.isBlank()) {
            return Optional.empty();
        }
        if (shouldBlockExternalLookup(qualifiedType, reason)) {
            return Optional.empty();
        }
        if (index.external().containsType(qualifiedType)) {
            return Optional.of(qualifiedType);
        }
        if (compiler.findAnywhere(qualifiedType).isPresent()) {
            return Optional.of(qualifiedType);
        }
        return Optional.empty();
    }

    private boolean shouldBlockExternalLookup(String qualifiedType, String reason) {
        if (!isWorkspaceOwnedCandidate(qualifiedType)) {
            return false;
        }
        LOG.fine(
                String.format(
                        "[workspace-boundary] external_leak candidate=%s reason=%s",
                        qualifiedType, reason));
        return true;
    }

    private boolean workspaceTypeExists(String qualifiedType) {
        if (qualifiedType == null || qualifiedType.isBlank()) {
            return false;
        }
        if (index.workspace().containsType(qualifiedType)) {
            return true;
        }
        return compiler.findTypeDeclaration(qualifiedType) != CompilerProvider.NOT_FOUND;
    }

    private LinkedHashSet<String> scopedCandidates(String raw, CompilationUnitTree root) {
        var candidates = new LinkedHashSet<String>();
        for (var importTree : root.getImports()) {
            if (importTree.isStatic()) {
                continue;
            }
            var imported = importTree.getQualifiedIdentifier().toString();
            if (imported.endsWith("." + raw)) {
                candidates.add(imported);
            }
            if (imported.endsWith(".*")) {
                candidates.add(imported.substring(0, imported.length() - 1) + raw);
            }
        }
        var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!packageName.isBlank()) {
            candidates.add(packageName + "." + raw);
        }
        return candidates;
    }

    private String normalizeTypeName(String typeName) {
        var raw = typeName.trim();
        while (raw.endsWith("[]")) {
            raw = raw.substring(0, raw.length() - 2);
        }
        var genericStart = raw.indexOf('<');
        if (genericStart >= 0) {
            raw = raw.substring(0, genericStart);
        }
        if (raw.startsWith("? extends ")) {
            raw = raw.substring("? extends ".length()).trim();
        } else if (raw.startsWith("? super ")) {
            raw = raw.substring("? super ".length()).trim();
        } else if ("?".equals(raw)) {
            return "";
        }
        return raw.replace('$', '.').trim();
    }
}
