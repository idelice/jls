package org.javacs;

import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.JavaFileObject;

public class SourceFileObject implements JavaFileObject {
    /** path is the absolute path to this file on disk */
    final Path path;
    /** contents is the text in this file, or null if we should use the text in FileStore */
    final String contents;
    /** if contents is set, the modified time of contents */
    final Instant modified;
    final boolean pruned;

    public SourceFileObject(Path path) {
        this(path, false);
    }

    public SourceFileObject(Path path, boolean pruned) {
        this(path, null, Instant.EPOCH, pruned);
    }

    public SourceFileObject(Path path, String contents, Instant modified) {
        this(path, contents, modified, false);
    }

    public SourceFileObject(Path path, String contents, Instant modified, boolean pruned) {
        if (!FileStore.isJavaFile(path)) throw new RuntimeException(path + " is not a java source");
        this.path = path;
        this.contents = contents;
        this.modified = modified;
        this.pruned = pruned;
    }

    @Override
    public boolean equals(Object other) {
        if (other.getClass() != SourceFileObject.class) return false;
        var that = (SourceFileObject) other;
        return this.path.equals(that.path) && this.pruned == that.pruned;
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, pruned);
    }

    @Override
    public Kind getKind() {
        var name = path.getFileName().toString();
        return kindFromExtension(name);
    }

    private static Kind kindFromExtension(String name) {
        for (var candidate : Kind.values()) {
            if (name.endsWith(candidate.extension)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public boolean isNameCompatible(String simpleName, Kind kind) {
        return path.getFileName().toString().equals(simpleName + kind.extension);
    }

    @Override
    public NestingKind getNestingKind() {
        return null;
    }

    @Override
    public Modifier getAccessLevel() {
        return null;
    }

    @Override
    public URI toUri() {
        return path.toUri();
    }

    @Override
    public String getName() {
        return path.toString();
    }

    @Override
    public InputStream openInputStream() {
        if (contents != null) {
            var bytes = contents.getBytes();
            return new ByteArrayInputStream(bytes);
        }
        if (pruned) {
            var bytes = Parser.pruneBody(path).getBytes();
            return new ByteArrayInputStream(bytes);
        }
        return FileStore.inputStream(path);
    }

    @Override
    public OutputStream openOutputStream() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Reader openReader(boolean ignoreEncodingErrors) {
        if (contents != null) {
            return new StringReader(contents);
        }
        if (pruned) {
            return new StringReader(Parser.pruneBody(path));
        }
        return FileStore.bufferedReader(path);
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        if (contents != null) {
            return contents;
        }
        if (pruned) {
            return Parser.pruneBody(path);
        }
        return FileStore.contents(path);
    }

    @Override
    public Writer openWriter() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getLastModified() {
        if (contents != null) {
            return modified.toEpochMilli();
        }
        var fileModified = FileStore.modified(path);
        if (fileModified == null) return 0;
        return fileModified.toEpochMilli();
    }

    @Override
    public boolean delete() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
        return path.toString();
    }
}
