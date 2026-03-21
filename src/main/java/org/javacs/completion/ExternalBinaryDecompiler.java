package org.javacs.completion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

final class ExternalBinaryDecompiler {
    private static final Logger LOG = Logger.getLogger("main");

    private final Set<Path> classPathRoots;
    private final String classPathFingerprint;
    private final ClassLoader classLoader;

    ExternalBinaryDecompiler(Set<Path> classPathRoots, String classPathFingerprint, ClassLoader classLoader) {
        this.classPathRoots = classPathRoots == null ? Set.of() : Set.copyOf(classPathRoots);
        this.classPathFingerprint = classPathFingerprint == null ? "" : classPathFingerprint;
        this.classLoader = classLoader == null ? ExternalBinaryDecompiler.class.getClassLoader() : classLoader;
    }

    Optional<Path> decompileSourcePath(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank() || classPathRoots.isEmpty()) {
            return Optional.empty();
        }
        var target = findBinaryTarget(qualifiedName);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        return decompile(target.get());
    }

    private Optional<Path> decompile(BinaryTarget target) {
        var outputRoot = decompiledOutputRoot(target.topLevelQualifiedName());
        var outputFile = findGeneratedSource(outputRoot, simpleName(target.topLevelQualifiedName()) + ".java");
        if (Files.isRegularFile(outputFile)) {
            return Optional.of(outputFile);
        }
        var inputRoot =
                Path.of(System.getProperty("java.io.tmpdir"))
                        .resolve("jls-binary-inputs")
                        .resolve(classPathFingerprint)
                        .resolve(target.topLevelQualifiedName().replace('.', '/'));
        try {
            Files.createDirectories(inputRoot);
            Files.createDirectories(outputRoot);
            var inputs = materializeInputs(target, inputRoot);
            if (inputs.isEmpty()) {
                return Optional.empty();
            }
            try (var saver = new SourceFileSaver(outputRoot)) {
                var builder =
                        Decompiler.builder()
                                .inputs(inputs.stream().map(Path::toFile).toArray(java.io.File[]::new))
                                .libraries(
                                        classPathRoots.stream()
                                                .filter(Files::exists)
                                                .map(Path::toFile)
                                                .toArray(java.io.File[]::new))
                                .output(saver)
                                .logger(IFernflowerLogger.NO_OP)
                                .option(IFernflowerPreferences.REMOVE_SYNTHETIC, "1")
                                .option(IFernflowerPreferences.REMOVE_BRIDGE, "1")
                                .option(IFernflowerPreferences.DECOMPILER_COMMENTS, "0")
                                .option(IFernflowerPreferences.SOURCE_FILE_COMMENTS, "0")
                                .option(IFernflowerPreferences.USE_METHOD_PARAMETERS, "1")
                                .option(IFernflowerPreferences.DECOMPILE_PREVIEW, "1")
                                .option(IFernflowerPreferences.INDENT_STRING, "    ");
                builder.build().decompile();
            }
            var generated = findGeneratedSource(outputRoot, simpleName(target.topLevelQualifiedName()) + ".java");
            return Files.isRegularFile(generated) ? Optional.of(generated) : Optional.empty();
        } catch (IOException | RuntimeException ex) {
            LOG.fine(
                    String.format(
                            "[external-binary] vineflower miss type=%s reason=%s",
                            target.requestedQualifiedName(),
                            ex.getClass().getSimpleName()));
            return Optional.empty();
        }
    }

    private List<Path> materializeInputs(BinaryTarget target, Path inputRoot) throws IOException {
        var files = new ArrayList<Path>();
        var topLevelRelative = target.topLevelRelativePath();
        var topLevelPrefix = topLevelRelative.substring(0, topLevelRelative.length() - ".class".length());
        if (Files.isDirectory(target.root())) {
            var topLevelFile = target.root().resolve(topLevelRelative);
            if (!Files.isRegularFile(topLevelFile)) {
                return List.of();
            }
            var copiedTopLevel = copyToInputRoot(inputRoot, topLevelRelative, Files.readAllBytes(topLevelFile));
            files.add(copiedTopLevel);
            var classDir = topLevelFile.getParent();
            if (classDir != null && Files.isDirectory(classDir)) {
                var topLevelName = topLevelFile.getFileName().toString();
                var innerPrefix = topLevelName.substring(0, topLevelName.length() - ".class".length()) + "$";
                try (var stream = Files.list(classDir)) {
                    for (var child : stream.collect(Collectors.toList())) {
                        var fileName = child.getFileName().toString();
                        if (!fileName.startsWith(innerPrefix) || !fileName.endsWith(".class")) {
                            continue;
                        }
                        var relative = topLevelRelative.substring(0, topLevelRelative.lastIndexOf('/') + 1) + fileName;
                        files.add(copyToInputRoot(inputRoot, relative, Files.readAllBytes(child)));
                    }
                }
            }
            return files;
        }
        if (!Files.isRegularFile(target.root())) {
            return List.of();
        }
        try (var jar = new JarFile(target.root().toFile())) {
            var entry = jar.getJarEntry(topLevelRelative);
            if (entry == null) {
                return List.of();
            }
            try (var in = jar.getInputStream(entry)) {
                files.add(copyToInputRoot(inputRoot, topLevelRelative, in.readAllBytes()));
            }
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var next = entries.nextElement();
                var name = next.getName();
                if (!name.startsWith(topLevelPrefix + "$") || !name.endsWith(".class")) {
                    continue;
                }
                try (var in = jar.getInputStream(next)) {
                    files.add(copyToInputRoot(inputRoot, name, in.readAllBytes()));
                }
            }
        }
        return files;
    }

    private Path copyToInputRoot(Path inputRoot, String relativePath, byte[] bytes) throws IOException {
        var output = inputRoot.resolve(relativePath);
        Files.createDirectories(output.getParent());
        Files.write(output, bytes);
        return output;
    }

    private Path decompiledOutputRoot(String topLevelQualifiedName) {
        return Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("jls-binary-decompiled")
                .resolve(classPathFingerprint)
                .resolve(topLevelQualifiedName.replace('.', '_'));
    }

    private Path findGeneratedSource(Path outputRoot, String fileName) {
        if (!Files.exists(outputRoot)) {
            return outputRoot.resolve(fileName);
        }
        try (var stream = Files.walk(outputRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElse(outputRoot.resolve(fileName));
        } catch (IOException ignored) {
            return outputRoot.resolve(fileName);
        }
    }

    private Optional<BinaryTarget> findBinaryTarget(String qualifiedName) {
        for (var binaryName : binaryNameCandidates(qualifiedName)) {
            var relative = binaryName.replace('.', '/') + ".class";
            for (var root : classPathRoots) {
                if (Files.isDirectory(root) && Files.isRegularFile(root.resolve(relative))) {
                    return Optional.of(createTarget(qualifiedName, root, binaryName));
                }
                if (Files.isRegularFile(root)) {
                    try (var jar = new JarFile(root.toFile())) {
                        if (jar.getJarEntry(relative) != null) {
                            return Optional.of(createTarget(qualifiedName, root, binaryName));
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        var reflective = resolveReflectiveTopLevel(qualifiedName);
        if (reflective.isPresent()) {
            var target = reflective.get();
            for (var root : classPathRoots) {
                if (Files.isDirectory(root) && Files.isRegularFile(root.resolve(target.topLevelRelativePath()))) {
                    return Optional.of(target.withRoot(root));
                }
                if (Files.isRegularFile(root)) {
                    try (var jar = new JarFile(root.toFile())) {
                        if (jar.getJarEntry(target.topLevelRelativePath()) != null) {
                            return Optional.of(target.withRoot(root));
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<BinaryTarget> resolveReflectiveTopLevel(String qualifiedName) {
        for (var binaryName : binaryNameCandidates(qualifiedName)) {
            try {
                var cls = Class.forName(binaryName, false, classLoader);
                var topLevel = cls;
                while (topLevel.getEnclosingClass() != null) {
                    topLevel = topLevel.getEnclosingClass();
                }
                var topLevelBinaryName = topLevel.getName();
                return Optional.of(
                        new BinaryTarget(
                                qualifiedName,
                                null,
                                topLevelBinaryName.replace('$', '.'),
                                topLevelBinaryName.replace('.', '/') + ".class"));
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }
        return Optional.empty();
    }

    private BinaryTarget createTarget(String requestedQualifiedName, Path root, String binaryName) {
        var topLevelBinaryName = binaryName.contains("$") ? binaryName.substring(0, binaryName.indexOf('$')) : binaryName;
        return new BinaryTarget(
                requestedQualifiedName,
                root,
                topLevelBinaryName.replace('$', '.'),
                topLevelBinaryName.replace('.', '/') + ".class");
    }

    private static List<String> binaryNameCandidates(String qualifiedName) {
        var candidates = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        var candidate = qualifiedName;
        while (candidate != null && seen.add(candidate)) {
            candidates.add(candidate);
            var dot = candidate.lastIndexOf('.');
            candidate = dot < 0 ? null : candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);
        }
        return candidates;
    }

    private static String simpleName(String qualifiedType) {
        var index = qualifiedType.lastIndexOf('.');
        return index >= 0 ? qualifiedType.substring(index + 1) : qualifiedType;
    }

    private record BinaryTarget(
            String requestedQualifiedName, Path root, String topLevelQualifiedName, String topLevelRelativePath) {
        BinaryTarget withRoot(Path resolvedRoot) {
            return new BinaryTarget(requestedQualifiedName, resolvedRoot, topLevelQualifiedName, topLevelRelativePath);
        }
    }

    private static final class SourceFileSaver implements IResultSaver {
        private final Path outputRoot;

        SourceFileSaver(Path outputRoot) {
            this.outputRoot = outputRoot;
        }

        @Override
        public void saveFolder(String path) {
            try {
                Files.createDirectories(resolve(path));
            } catch (IOException ignored) {
            }
        }

        @Override
        public void copyFile(String source, String path, String entryName) {}

        @Override
        public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
            write(path, entryName, content);
        }

        @Override
        public void createArchive(String path, String archiveName, java.util.jar.Manifest manifest) {}

        @Override
        public void saveDirEntry(String path, String archiveName, String entryName) {}

        @Override
        public void copyEntry(String source, String path, String archiveName, String entry) {}

        @Override
        public void saveClassEntry(
                String path, String archiveName, String qualifiedName, String entryName, String content) {
            write(path, entryName, content);
        }

        @Override
        public void closeArchive(String path, String archiveName) {}

        private void write(String path, String entryName, String content) {
            try {
                var file = resolve(path).resolve(entryName);
                Files.createDirectories(file.getParent());
                Files.writeString(file, content);
            } catch (IOException ignored) {
            }
        }

        private Path resolve(String path) {
            if (path == null || path.isBlank()) {
                return outputRoot;
            }
            return outputRoot.resolve(path);
        }
    }
}
