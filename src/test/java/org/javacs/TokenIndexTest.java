package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.javacs.index.TokenIndex;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TokenIndexTest {
    private static Path workspaceRoot;
    private static Path neverIndexed;
    private static Path emptyTokens;

    private TokenIndex index;

    @BeforeClass
    public static void createWorkspace() throws IOException {
        workspaceRoot = Files.createTempDirectory("token-index-test-");
        neverIndexed = workspaceRoot.resolve("NeverIndexed.java");
        emptyTokens = workspaceRoot.resolve("EmptyTokens.java");
        Files.writeString(neverIndexed, "class NeverIndexed {}\n");
        Files.writeString(emptyTokens, "class EmptyTokens {}\n");
    }

    @Before
    public void resetStore() {
        FileStore.reset();
        FileStore.setWorkspaceRoots(Set.of(workspaceRoot));
        index = new TokenIndex();
    }

    @After
    public void clearStore() {
        FileStore.reset();
    }

    @AfterClass
    public static void deleteWorkspace() throws IOException {
        List<Path> paths;
        try (var walk = Files.walk(workspaceRoot)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (var path : paths) Files.deleteIfExists(path);
    }

    @Test
    public void replaceFileExposesFileUnderEveryToken() {
        var file = workspaceRoot.resolve("EveryToken.java");
        index.replaceFile(file, Set.of("alpha", "beta"));

        assertThat(index.filesContainingToken("alpha"), hasItem(file));
        assertThat(index.filesContainingToken("beta"), hasItem(file));
    }

    @Test
    public void replacingFileRemovesOldPostings() {
        var file = workspaceRoot.resolve("Replaced.java");
        index.replaceFile(file, Set.of("before"));
        index.replaceFile(file, Set.of("after"));

        assertThat(index.filesContainingToken("before"), not(hasItem(file)));
        assertThat(index.filesContainingToken("after"), hasItem(file));
    }

    @Test
    public void removeFileDropsAllPostings() {
        var file = workspaceRoot.resolve("Removed.java");
        index.replaceFile(file, Set.of("alpha", "beta"));
        index.removeFile(file);

        assertThat(index.filesContainingToken("alpha"), not(hasItem(file)));
        assertThat(index.filesContainingToken("beta"), not(hasItem(file)));
    }

    @Test
    public void unindexedFileIsReturnedByFallback() {
        assertThat(index.filesContainingToken("anything"), hasItem(neverIndexed));
    }

    @Test
    public void emptyTokenFileIsNotReturnedByFallback() {
        index.replaceFile(emptyTokens, Set.of());

        assertThat(index.filesContainingToken("anything"), not(hasItem(emptyTokens)));
    }

    @Test
    public void replaceFilesUpdatesOnlyListedFiles() {
        var destination = new TokenIndex();
        var source = new TokenIndex();
        var first = workspaceRoot.resolve("MergeFirst.java");
        var second = workspaceRoot.resolve("MergeSecond.java");
        var untouched = workspaceRoot.resolve("MergeUntouched.java");
        destination.replaceFile(first, Set.of("oldFirst"));
        destination.replaceFile(second, Set.of("oldSecond"));
        destination.replaceFile(untouched, Set.of("oldUntouched"));
        source.replaceFile(first, Set.of("newFirst"));
        source.replaceFile(second, Set.of("newSecond"));

        destination.replaceFiles(source, List.of(first));

        assertThat(destination.filesContainingToken("newFirst"), hasItem(first));
        assertThat(destination.filesContainingToken("oldFirst"), not(hasItem(first)));
        assertThat(destination.filesContainingToken("oldSecond"), hasItem(second));
        assertThat(destination.filesContainingToken("newSecond"), not(hasItem(second)));
        assertThat(destination.filesContainingToken("oldUntouched"), hasItem(untouched));
    }

    @Test
    public void replaceWithSwapsWholeIndex() {
        var destination = new TokenIndex();
        var source = new TokenIndex();
        var oldFile = workspaceRoot.resolve("OldOwner.java");
        var newFile = workspaceRoot.resolve("NewOwner.java");
        destination.replaceFile(oldFile, Set.of("oldToken"));
        source.replaceFile(newFile, Set.of("newToken"));

        destination.replaceWith(source);

        assertThat(destination.filesContainingToken("newToken"), hasItem(newFile));
        assertThat(destination.filesContainingToken("oldToken"), not(hasItem(oldFile)));
        assertThat(source.filesContainingToken("newToken"), not(hasItem(newFile)));
    }
}
