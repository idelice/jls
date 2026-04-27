package org.javacs;

import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class CompilerMemoryInvestigationTest {
    @Test
    public void diagnosticsInThisRepoRetainsWorkspaceWideFullCompile() throws Exception {
        var workspace = Path.of(".").toAbsolutePath().normalize();
        var server = LanguageServerFixture.getJavaLanguageServer(workspace, diagnostic -> {});
        var file = workspace.resolve("src/main/java/org/javacs/JavaCompilerService.java");

        var workspaceJavaFiles = FileStore.all().size();
        server.getOrCreateCompiler().logMemorySnapshot("test_before_lint workspace_files=" + workspaceJavaFiles);

        server.lint(List.of(file));
        forceGc();

        var snapshot = server.getOrCreateCompiler().memorySnapshot("test_after_lint");
        server.getOrCreateCompiler().logMemorySnapshot("test_after_lint workspace_files=" + workspaceJavaFiles);

        assertTrue("expected this repo workspace to contain multiple Java files", workspaceJavaFiles > 10);
        assertTrue(
                "one-file diagnostics should not retain a workspace-wide FULL compile; retained roots="
                        + snapshot.fullRoots()
                        + " workspaceFiles="
                        + workspaceJavaFiles
                        + " heapUsedMb="
                        + snapshot.heapUsedMb(),
                snapshot.fullRoots() <= 1);
    }

    private static void forceGc() throws InterruptedException {
        System.gc();
        Thread.sleep(100);
        System.gc();
        Thread.sleep(100);
    }
}
