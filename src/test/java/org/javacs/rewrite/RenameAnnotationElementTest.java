package org.javacs.rewrite;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.LanguageServerFixture;
import org.javacs.lsp.Position;
import org.javacs.lsp.TextEdit;
import org.junit.Test;

public class RenameAnnotationElementTest {
    static final CompilerProvider compiler = LanguageServerFixture.getCompilerProvider();

    private Path example(String name) {
        return LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve("src/org/javacs/example").resolve(name).toAbsolutePath();
    }

    private Path fixture(String name) {
        return LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve("src/org/javacs/rewrite").resolve(name).toAbsolutePath();
    }

    private int offset(String text, Position position) {
        return FileStore.offset(text, position.line + 1, position.character + 1);
    }

    private String apply(Path file, TextEdit[] edits) throws IOException {
        var text = Files.readString(file);
        var ordered = Arrays.stream(edits)
                .sorted(Comparator.comparingInt((TextEdit edit) -> offset(text, edit.range.start))
                        .reversed())
                .toList();
        var result = new StringBuilder(text);
        for (var edit : ordered) {
            result.replace(offset(text, edit.range.start), offset(text, edit.range.end), edit.newText);
        }
        return result.toString();
    }

    private long zeroWidth(TextEdit[] edits) {
        return Arrays.stream(edits)
                .filter(edit -> edit.range.start.line == edit.range.end.line
                        && edit.range.start.character == edit.range.end.character)
                .count();
    }

    @Test
    public void renameValueRewritesShorthandAndExplicit() throws IOException {
        var edits = new RenameMethod("org.javacs.example.AnnotationElementDef", "value", new String[] {}, "renamed")
                .rewrite(compiler);

        var def = example("AnnotationElementDef.java");
        var use = example("AnnotationElementUse.java");
        var shorthand = example("AnnotationElementShorthand.java");

        assertThat(edits.keySet(), hasSize(3));
        assertThat(edits, hasKey(def));
        assertThat(edits, hasKey(use));
        assertThat(edits, hasKey(shorthand));

        var shorthandText = apply(shorthand, edits.get(shorthand));
        var useText = apply(use, edits.get(use));
        assertThat(shorthandText, containsString("@AnnotationElementDef(renamed = \"only-shorthand\")"));
        assertThat(useText, containsString("renamed = \"shorthand\""));
        assertThat(useText, containsString("renamed = \"explicit\""));

        assertThat(zeroWidth(edits.get(def)), is(0L));
        assertThat(zeroWidth(edits.get(use)), is(1L));
        assertThat(zeroWidth(edits.get(shorthand)), is(1L));
    }

    @Test
    public void arrayShorthandBecomesNamed() throws IOException {
        var edits = new RenameMethod("org.javacs.rewrite.TestRenameAnnotationArray", "value", new String[] {}, "renamed")
                .rewrite(compiler);

        var def = fixture("TestRenameAnnotationArray.java");
        var use = fixture("TestRenameAnnotationArrayUse.java");

        assertThat(edits, hasKey(def));
        assertThat(edits, hasKey(use));

        var useText = apply(use, edits.get(use));
        assertThat(useText, containsString("renamed = {\"a\", \"b\"}"));
        assertThat(useText, containsString("renamed = {}"));
        assertThat(edits.get(use).length, is(2));
        assertThat(zeroWidth(edits.get(use)), is(2L));
    }

    @Test
    public void nestedAnnotationKeepsInnerShorthand() throws IOException {
        var edits = new RenameMethod("org.javacs.rewrite.TestRenameOuter", "value", new String[] {}, "renamed")
                .rewrite(compiler);

        var def = fixture("TestRenameOuter.java");
        var innerDef = fixture("TestRenameInner.java");
        var use = fixture("TestRenameOuterUse.java");

        assertThat(edits, hasKey(def));
        assertThat(edits, hasKey(use));
        assertThat(edits, not(hasKey(innerDef)));

        var useText = apply(use, edits.get(use));
        assertThat(useText, containsString("@TestRenameOuter(renamed = @TestRenameInner(\"x\"))"));
        assertThat(edits.get(use).length, is(1));
        assertThat(zeroWidth(edits.get(use)), is(1L));
    }

    @Test
    public void ordinaryValueMethodAddsNoInsertion() throws IOException {
        var edits = new RenameMethod("org.javacs.rewrite.TestRenameOrdinaryValue", "value", new String[] {}, "renamed")
                .rewrite(compiler);

        var def = fixture("TestRenameOrdinaryValue.java");
        var use = fixture("TestRenameOrdinaryValueUse.java");

        assertThat(edits, hasKey(def));
        assertThat(edits, hasKey(use));
        var insertions = edits.values().stream().mapToLong(this::zeroWidth).sum();
        assertThat(insertions, is(0L));
        assertThat(apply(def, edits.get(def)), containsString("int renamed()"));
        assertThat(apply(use, edits.get(use)), containsString(".renamed()"));
    }

    @Test
    public void nonValueElementAddsNoInsertion() throws IOException {
        var edits = new RenameMethod(
                        "org.javacs.rewrite.TestRenameNamed", "name", new String[] {}, "renamed")
                .rewrite(compiler);

        var def = fixture("TestRenameNamed.java");
        var use = fixture("TestRenameNamedUse.java");

        assertThat(edits, hasKey(def));
        assertThat(edits, hasKey(use));
        var insertions = edits.values().stream().mapToLong(this::zeroWidth).sum();
        assertThat(insertions, is(0L));
        assertThat(apply(def, edits.get(def)), containsString("String renamed();"));
        assertThat(apply(use, edits.get(use)), containsString("renamed = \"x\""));
    }

    @Test
    public void qualifiedShorthandBecomesNamed() throws IOException {
        var edits = new RenameMethod("org.javacs.example.TestRenameQualifiedDef", "value", new String[] {}, "renamed")
                .rewrite(compiler);

        var use = fixture("TestRenameQualifiedShorthandUse.java");

        assertThat(edits, hasKey(use));
        assertThat(
                apply(use, edits.get(use)),
                containsString("@org.javacs.example.TestRenameQualifiedDef(renamed = \"x\")"));
        assertThat(zeroWidth(edits.get(use)), is(1L));
    }
}
