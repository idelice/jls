package org.javacs.maven;

import java.io.*;
import java.util.*;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.*;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.*;
import org.eclipse.aether.resolution.*;

/** Runs inside Maven, using its effective projects, repositories, mediation and partial results. */
public final class SourceDependencies extends AbstractMavenLifecycleParticipant {
    private ProjectDependenciesResolver projectResolver;
    private RepositorySystem repositorySystem;

    @Override public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        String output = session.getUserProperties().getProperty("jls.dependencies.output");
        if (output == null) return;
        String selected = session.getUserProperties().getProperty("jls.dependencies.project");
        Map<String, MavenProject> projects = new LinkedHashMap<>();
        for (MavenProject project : session.getAllProjects()) projects.put(project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion(), project);
        MavenProject target = projects.get(selected);
        if (target == null) throw new MavenExecutionException("JLS module not found: " + selected, (File) null);
        Properties result = new Properties();
        result.setProperty("complete", "true");
        resolveScope(session, target, projects, false, result);
        resolveScope(session, target, projects, true, result);
        try (OutputStream stream = new FileOutputStream(output)) {
            result.store(stream, "JLS external dependencies; workspace artifacts resolved as sources");
        } catch (IOException failure) {
            throw new MavenExecutionException("Cannot write JLS dependencies", failure);
        }
    }

    private void resolveScope(MavenSession session, MavenProject target, Map<String, MavenProject> projects,
            boolean tests, Properties output) {
        String prefix = tests ? "test" : "main";
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<MavenProject> pending = new ArrayDeque<>();
        pending.add(target);
        while (!pending.isEmpty()) {
            MavenProject project = pending.removeFirst();
            if (!visited.add(project.getId())) continue;
            boolean includeTests = tests && project == target;
            for (String root : project.getCompileSourceRoots()) output.setProperty(prefix + ".source." + root, root);
            if (includeTests) for (String root : project.getTestCompileSourceRoots()) output.setProperty(prefix + ".source." + root, root);
            DependencyFilter filter = (node, parents) -> node.getDependency() == null
                    || (visible(node.getDependency().getScope(), includeTests)
                        && !owns(projects, node.getDependency().getArtifact()));
            DependencyResolutionResult resolved;
            try {
                resolved = projectResolver.resolve(new DefaultDependencyResolutionRequest(project,
                        session.getRepositorySession()).setResolutionFilter(filter));
            } catch (org.apache.maven.project.DependencyResolutionException failure) {
                resolved = failure.getResult();
                output.setProperty("complete", "false");
                System.err.println("[jls-maven] partial project=" + project.getId() + " scope=" + prefix);
            }
            for (Dependency dependency : resolved.getResolvedDependencies()) addArtifact(artifacts, dependency.getArtifact());
            DependencyNode graph = resolved.getDependencyGraph();
            if (graph == null) continue;
            if (!resolved.getCollectionErrors().isEmpty()) recover(session, graph, filter, artifacts);
            graph.accept(new DependencyVisitor() {
                public boolean visitEnter(DependencyNode node) {
                    Dependency dependency = node.getDependency();
                    if (dependency != null && visible(dependency.getScope(), includeTests)) {
                        Artifact artifact = dependency.getArtifact();
                        MavenProject source = projects.get(projectId(artifact));
                        if (source != null && owns(projects, artifact)) {
                            pending.add(source);
                            if ("tests".equals(artifact.getClassifier())) {
                                for (String root : source.getTestCompileSourceRoots()) output.setProperty(prefix + ".source." + root, root);
                            }
                        }
                    }
                    return true;
                }
                public boolean visitLeave(DependencyNode node) { return true; }
            });
        }
        int index = 0;
        for (Artifact artifact : artifacts.values()) output.setProperty(prefix + ".path." + index++, artifact.getFile().getAbsolutePath());
    }

    private void recover(MavenSession session, DependencyNode graph, DependencyFilter filter, Map<String, Artifact> artifacts) {
        DependencyResult recovered;
        try {
            recovered = repositorySystem.resolveDependencies(session.getRepositorySession(), new DependencyRequest(graph, filter));
        } catch (org.eclipse.aether.resolution.DependencyResolutionException failure) {
            recovered = failure.getResult();
        }
        for (ArtifactResult artifact : recovered.getArtifactResults()) {
            if (artifact.isResolved()) addArtifact(artifacts, artifact.getArtifact());
        }
    }

    private static void addArtifact(Map<String, Artifact> artifacts, Artifact artifact) {
        if (artifact.getFile() == null || !artifact.getFile().isFile()) return;
        String key = artifact.getGroupId() + ':' + artifact.getArtifactId() + ':' + artifact.getExtension() + ':' + artifact.getClassifier();
        artifacts.putIfAbsent(key, artifact);
    }

    private static boolean visible(String scope, boolean tests) {
        return tests || "compile".equals(scope) || "provided".equals(scope) || "system".equals(scope);
    }

    private static boolean owns(Map<String, MavenProject> projects, Artifact artifact) {
        return projects.containsKey(projectId(artifact)) && "jar".equals(artifact.getExtension())
                && (artifact.getClassifier().isEmpty() || "tests".equals(artifact.getClassifier()));
    }

    private static String projectId(Artifact artifact) {
        return artifact.getGroupId() + ':' + artifact.getArtifactId() + ':' + artifact.getBaseVersion();
    }
}
