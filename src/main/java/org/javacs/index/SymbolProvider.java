package org.javacs.index;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.javacs.CompilerProvider;
import org.javacs.ParseTask;
import org.javacs.lsp.SymbolInformation;

public class SymbolProvider {

    final CompilerProvider compiler;

    public SymbolProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public List<SymbolInformation> findSymbols(String query, int limit) {
        LOG.info(String.format("Searching for `%s`...", query));
        var result = new ArrayList<SymbolInformation>();
        var checked = 0;
        var parsed = 0;
        for (var file : compiler.search(query)) {
            checked++;
            // Parse the file and check class members for matches
            LOG.info(String.format("...%s contains text matches", file.getFileName()));
            var task = compiler.parse(file);
            var symbols = findSymbolsMatching(task, query);
            parsed++;
            // If we confirm matches, add them to the results
            if (symbols.size() > 0) {
                LOG.info(String.format("...found %d occurrences", symbols.size()));
            }
            result.addAll(symbols);
            // If results are full, stop
            if (result.size() >= limit) break;
        }

        return result;
    }

    public List<SymbolInformation> documentSymbols(Path file) {
        var task = compiler.parse(file);
        return findSymbolsMatching(task, "");
    }

    private List<SymbolInformation> findSymbolsMatching(ParseTask task, String query) {
        var found = new ArrayList<SymbolInformation>();
        new FindSymbolsMatching(task, query).scan(task.root, found);
        return found;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
