package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.javacs.lsp.*;
import org.junit.BeforeClass;
import org.junit.Test;

public class SearchTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @BeforeClass
    public static void openSource() throws IOException {
        var uri = FindResource.uri("/org/javacs/example/AutocompleteBetweenLines.java");
        var textContent = new StringJoiner("\n");
        for (var line : Files.readAllLines(Paths.get(uri))) {
            textContent.add(line);
        }
        var document = new TextDocumentItem();
        document.uri = uri;
        document.text = textContent.toString();
        server.didOpenTextDocument(new DidOpenTextDocumentParams(document));
    }

    private static Set<String> searchWorkspace(String query, int limit) {
        return server.workspaceSymbols(new WorkspaceSymbolParams(query))
                .stream()
                .map(result -> result.name)
                .limit(limit)
                .collect(Collectors.toSet());
    }

    @Test
    public void all() {
        var all = searchWorkspace("", 100);

        assertThat(all, not(empty()));
    }

    @Test
    public void searchClasses() {
        var all = searchWorkspace("ABetweenLines", Integer.MAX_VALUE);

        assertThat(all, hasItem("AutocompleteBetweenLines"));
    }

    @Test
    public void searchMethods() {
        var all = searchWorkspace("mStatic", Integer.MAX_VALUE);

        assertThat(all, hasItem("methodStatic"));
    }

}
