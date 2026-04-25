package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.Before;
import org.junit.Test;

public class CacheTest {

    @Before
    public void resetFileStore() {
        FileStore.reset();
    }

    @Test
    public void staleEntryInvalidatesAllKeysForFile() throws Exception {
        var file = Files.createTempFile("cache-test", ".java");
        Files.writeString(file, "class A {}");
        FileStore.externalCreate(file);

        var cache = new Cache<String, String>("test.cache", 10);
        cache.load(file, "one", "1");
        cache.load(file, "two", "2");

        assertThat(cache.size(), is(2));
        assertThat(cache.needs(file, "one"), is(false));

        Files.writeString(file, "class A { int x; }");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(1)));
        FileStore.externalChange(file);

        assertThat(cache.needs(file, "one"), is(true));
        assertThat(cache.size(), is(0));
    }

    @Test
    public void maximumSizeIsBounded() throws Exception {
        var cache = new Cache<String, String>("test.cache", 2);

        for (var i = 0; i < 3; i++) {
            Path file = Files.createTempFile("cache-bound-" + i, ".java");
            Files.writeString(file, "class A" + i + " {}");
            FileStore.externalCreate(file);
            cache.load(file, "k" + i, "v" + i);
        }

        assertThat(cache.size(), equalTo(2));
    }
}
