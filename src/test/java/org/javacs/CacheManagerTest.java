package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.Test;

public class CacheManagerTest {
    @Test
    public void keepsInMemoryCacheWhenDiskWriteFails() throws Exception {
        var blockingFile = Files.createTempFile("jls-cache-block", ".tmp");
        var cache = new CacheManager(blockingFile);
        var workspace = Files.createTempDirectory("jls-workspace");

        var classPath = Set.of(Path.of("/tmp/a.jar"));
        var docPath = Set.of(Path.of("/tmp/a-sources.jar"));

        cache.saveCache(workspace, Set.of(), classPath, docPath);

        var loaded = cache.loadCache(workspace, Set.of());
        assertThat(loaded.isPresent(), is(true));
        assertThat(loaded.get().classPath, is(classPath));
        assertThat(loaded.get().docPath, is(docPath));
    }
}
