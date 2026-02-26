package org.javacs.completion;

import java.nio.file.Path;
import java.util.Optional;
import org.javacs.CompileTask;

public interface CompletionSnapshotProvider {
    Optional<Snapshot> acquire(Path file);

    interface Snapshot extends AutoCloseable {
        CompileTask task();

        boolean stale();

        @Override
        void close();
    }
}
