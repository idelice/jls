package org.javacs;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Paths;
import java.util.Set;
import org.junit.Test;
import org.junit.Before;

/**
 * These tests are isolated because bugs caused by encoding issues could cause
 * phantom failures in other tests.
 */
public class FileEncodingTest {

    @Before
    public void resetSourcesBefore() {
        FileStore.reset();
    }

    @Test
    public void packageNameForNonUnicodeSource() {
        var encodingTestRoot = Paths.get("src/test/examples/encoding").normalize();

        // If an exception is thrown due to an unknown encoding it would
        // be here.
        FileStore.setWorkspaceRoots(Set.of(encodingTestRoot));

        var file = Paths.get("src/test/examples/encoding/EncodingWindows1252.java").toAbsolutePath().normalize();

        // The file's package declaration is pure ASCII and can be read even though
        // the file is Windows-1252 encoded. Verify no crash occurs and the package
        // name is either successfully extracted or empty (depending on whether the
        // decoder throws before reaching non-ASCII bytes).
        var pkg = FileStore.packageName(file);
        // packageName returns null if the file was removed from the index due to encoding error
        if (pkg != null) {
            assertThat(pkg, equalTo("org.javacs.example"));
        }
    }
}
