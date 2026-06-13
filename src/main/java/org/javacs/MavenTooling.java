package org.javacs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Builds a module graph for Maven multi-module projects by parsing pom.xml files.
 *
 * <p>Unlike the Gradle Tooling API, we don't run Maven to get per-module classpaths — the root
 * {@code mvn dependency:list} already gives the union classpath. This class only resolves the
 * module dependency structure (which module depends on which) and source directories, so that
 * {@link JavaLanguageServer} can scope the workspace index to the active module's transitive
 * source files.
 *
 * <p>Per-module external classpath scoping (narrowing to only the active module's deps) is a
 * future improvement; for now all modules share the union classpath resolved at the root.
 */
public final class MavenTooling {
    private static final Logger LOG = Logger.getLogger("main");

    private MavenTooling() {}

    /**
     * Build a module graph from all pom.xml files under {@code workspaceRoot}.
     * Returns {@link ModuleGraph#EMPTY} if not a multi-module Maven project
     * (i.e. only one pom.xml at the root, or parsing fails).
     */
    public static ModuleGraph resolveModuleGraph(Path workspaceRoot) {
        var rootPom = workspaceRoot.resolve("pom.xml");
        if (!Files.exists(rootPom)) {
            return ModuleGraph.EMPTY;
        }

        // Collect all pom.xml paths
        List<Path> allPoms;
        try (var stream = Files.walk(workspaceRoot, 8)) {
            allPoms = stream
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> {
                        // Skip build output dirs
                        for (var part : workspaceRoot.relativize(p)) {
                            var s = part.toString();
                            if (s.equals("target") || s.equals("build")) return false;
                        }
                        return true;
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return ModuleGraph.EMPTY;
        }

        if (allPoms.size() <= 1) {
            // Single-module project — no scoping needed
            return ModuleGraph.EMPTY;
        }

        // First pass: parse each pom, collect groupId:artifactId -> module dir
        var coordToDir = new LinkedHashMap<String, Path>();
        var pomData = new LinkedHashMap<Path, PomData>();

        for (var pom : allPoms) {
            var data = parsePom(pom);
            if (data == null) continue;
            pomData.put(pom, data);
            if (data.groupId != null && data.artifactId != null) {
                coordToDir.put(data.groupId + ":" + data.artifactId, pom.getParent());
            }
        }

        // Second pass: build ModuleInfo for each module
        var modules = new LinkedHashMap<String, ModuleGraph.ModuleInfo>();
        for (var entry : pomData.entrySet()) {
            var pom = entry.getKey();
            var data = entry.getValue();
            var moduleDir = pom.getParent().toAbsolutePath().normalize();
            var projectPath = ":" + workspaceRoot.relativize(moduleDir).toString().replace("/", ":");
            if (moduleDir.equals(workspaceRoot.toAbsolutePath().normalize())) {
                projectPath = ":";
            }

            // Conventional Maven source dirs
            var sourceDirs = new ArrayList<Path>();
            for (var rel : List.of("src/main/java", "src/test/java")) {
                var dir = moduleDir.resolve(rel);
                if (Files.exists(dir)) sourceDirs.add(dir);
            }

            // Inter-module deps: find workspace modules whose coords match our <dependencies>
            var moduleDeps = new ArrayList<String>();
            for (var depCoord : data.dependencyCoords) {
                var depDir = coordToDir.get(depCoord);
                if (depDir != null) {
                    var depModuleDir = depDir.toAbsolutePath().normalize();
                    var depPath = ":" + workspaceRoot.relativize(depModuleDir).toString().replace("/", ":");
                    if (depModuleDir.equals(workspaceRoot.toAbsolutePath().normalize())) {
                        depPath = ":";
                    }
                    moduleDeps.add(depPath);
                }
            }

            modules.put(projectPath, new ModuleGraph.ModuleInfo(
                    projectPath, moduleDir,
                    List.copyOf(sourceDirs),
                    List.of(), // external classpath comes from root mvn dependency:list
                    List.copyOf(moduleDeps),
                    data.sourceCompatibility));
        }

        return new ModuleGraph(Collections.unmodifiableMap(modules));
    }

    // -------------------------------------------------------------------------
    // pom.xml parsing
    // -------------------------------------------------------------------------

    private record PomData(
            String groupId, String artifactId,
            List<String> dependencyCoords, String sourceCompatibility) {}

    private static PomData parsePom(Path pom) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var doc = factory.newDocumentBuilder().parse(pom.toFile());
            var root = doc.getDocumentElement();

            var groupId = textOf(root, "groupId");
            // Inherit groupId from parent if not declared
            if (groupId == null) {
                var parent = firstChild(root, "parent");
                if (parent != null) groupId = textOf(parent, "groupId");
            }
            var artifactId = textOf(root, "artifactId");

            // Collect dependency coordinates
            var deps = new ArrayList<String>();
            var depsEl = firstChild(root, "dependencies");
            if (depsEl != null) {
                for (var dep : elements(depsEl.getChildNodes())) {
                    if (!"dependency".equals(dep.getLocalName())) continue;
                    var dg = textOf(dep, "groupId");
                    var da = textOf(dep, "artifactId");
                    var scope = textOf(dep, "scope");
                    // Only compile/provided/test scopes contribute to classpath
                    if (dg != null && da != null && !"runtime".equals(scope)) {
                        deps.add(dg + ":" + da);
                    }
                }
            }

            // Source compatibility from maven-compiler-plugin or properties
            String sourceCompatibility = null;
            var props = firstChild(root, "properties");
            if (props != null) {
                var mv = textOf(props, "maven.compiler.release");
                if (mv == null) mv = textOf(props, "maven.compiler.source");
                sourceCompatibility = mv;
            }

            return new PomData(groupId, artifactId, List.copyOf(deps), sourceCompatibility);
        } catch (Exception e) {
            LOG.fine("[maven-module-graph] failed to parse " + pom + ": " + e.getMessage());
            return null;
        }
    }

    private static String textOf(Element parent, String localName) {
        var child = firstChild(parent, localName);
        if (child == null) return null;
        var text = child.getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static Element firstChild(Element parent, String localName) {
        if (parent == null) return null;
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            var node = children.item(i);
            if (node instanceof Element el && localName.equals(el.getLocalName())) return el;
        }
        return null;
    }

    private static List<Element> elements(NodeList nodes) {
        var result = new ArrayList<Element>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element el) result.add(el);
        }
        return result;
    }
}
