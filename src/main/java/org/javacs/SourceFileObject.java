package org.javacs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.tools.SimpleJavaFileObject;

public class SourceFileObject extends SimpleJavaFileObject {
    /** path is the absolute path to this file on disk */
    final Path path;
    /** contents is the text in this file, or null if we should use the text in FileStore */
    final String contents;
    /** if contents is set, the modified time of contents */
    final Instant modified;
    /** if contents is set from an open document, this is its LSP version, otherwise -1 */
    final int version;
    private final boolean dynamicLastModified;

    public SourceFileObject(Path path) {
        super(sourceUri(path), Kind.SOURCE);
        this.path = path;
        var active = FileStore.activeDocument(path);
        if (active != null) {
            this.contents = active.content;
            this.modified = active.modified;
            this.version = active.version;
        } else {
            this.contents = null;
            this.modified = Instant.EPOCH;
            this.version = -1;
        }
        this.dynamicLastModified = true;
    }

    public SourceFileObject(Path path, String contents, Instant modified) {
        this(path, contents, modified, -1);
    }

    public SourceFileObject(Path path, String contents, Instant modified, int version) {
        super(sourceUri(path), Kind.SOURCE);
        this.path = path;
        this.contents = contents;
        this.modified = modified;
        this.version = version;
        this.dynamicLastModified = false;
    }

    private static java.net.URI sourceUri(Path path) {
        if (!FileStore.isJavaFile(path)) throw new RuntimeException(path + " is not a java source");
        return path.toUri();
    }

    @Override
    public boolean equals(Object other) {
        if (other.getClass() != SourceFileObject.class) return false;
        var that = (SourceFileObject) other;
        return this.path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return this.path.hashCode();
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        if (contents != null) {
            return contents;
        }
        return FileStore.contents(path);
    }

    @Override
    public String getName() {
        return path.toString();
    }

    @Override
    public long getLastModified() {
        if (!dynamicLastModified) return modified.toEpochMilli();
        return FileStore.isDirty(path) ? Long.MAX_VALUE : diskModified(path);
    }

    private static long diskModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public String toString() {
        return path.toString();
    }

    int contentVersion() {
        return version;
    }

    Instant contentModified() {
        if (contents == null) {
            return null;
        }
        return modified;
    }
}
