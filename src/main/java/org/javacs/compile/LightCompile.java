package org.javacs.compile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;
import javax.tools.DiagnosticCollector;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.SourceFileObject;

/**
 * Lightweight compile path for completion: parse + attr with processors disabled where possible.
 * Falls back to full compile if anything goes wrong.
 */
public final class LightCompile {
    private static final Logger LOG = Logger.getLogger(LightCompile.class.getName());

    private LightCompile() {}

    /**
     * Attempt a fast compile for completion:
     * - Disable annotation processors (skip Lombok overhead)
     * - Keep classpath/docpath the same
     * Returns null if we should fall back to the normal compile.
     */
    public static CompileTask lightCompile(CompilerProvider compiler, List<SourceFileObject> sources) {
        try {
            var started = Instant.now();
            var task = compiler.compileLight(sources);
            LOG.info(String.format("...light compile in %dms", Duration.between(started, Instant.now()).toMillis()));
            return task;
        } catch (Exception e) {
            LOG.fine("Light compile failed, falling back: " + e.getMessage());
            return null;
        }
    }
}
