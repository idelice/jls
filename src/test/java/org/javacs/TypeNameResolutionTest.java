package org.javacs;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.resolve.TypeNames;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** The same public lookup policies must hold for live ASTs and stored source snapshots. */
@RunWith(Parameterized.class)
public class TypeNameResolutionTest {
    @Parameterized.Parameters(name = "{0}")
    public static Object[][] cases() {
        return new Object[][] {
            {"explicit import wins", "import other.Target; import wildcard.*;", Set.of("pkg.Target", "other.Target", "wildcard.Target"), "other.Target"},
            {"same package", "", Set.of("pkg.Target"), "pkg.Target"},
            {"wildcard import", "import other.*;", Set.of("other.Target"), "other.Target"},
            {"java.lang", "", Set.of("java.lang.Target"), "java.lang.Target"},
            {"ambiguous wildcards", "import first.*; import second.*;", Set.of("first.Target", "second.Target"), null},
            {"package and wildcard ambiguity preserved", "import other.*;", Set.of("pkg.Target", "other.Target"), null},
            {"package and java.lang ambiguity preserved", "", Set.of("pkg.Target", "java.lang.Target"), null},
            {"explicit nested import", "import other.Outer.Target;", Set.of("other.Outer.Target"), "other.Outer.Target"},
            {"static imports excluded", "import static other.Outer.Target;", Set.of("other.Outer.Target"), null},
            {"invisible explicit import", "import hidden.Target; import visible.*;", Set.of("visible.Target"), "visible.Target"},
            {"unknown type", "import other.*;", Set.of(), null}
        };
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String imports;
    @Parameterized.Parameter(2) public Set<String> visibleTypes;
    @Parameterized.Parameter(3) public String expected;
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void liveSourceLookupPreservesImportAndVisibilityRules() throws Exception {
        var parse = parse();
        assertEquals(Optional.ofNullable(expected), TypeNames.resolveSimpleName("Target", parse.root(), visibleTypes::contains));
    }

    @Test
    public void indexedSuperclassLookupPreservesImportAndVisibilityRules() throws Exception {
        var parse = parse();
        var index = WorkspaceTypeIndex.fromParseTrees(List.of(parse), visibleTypes::contains);
        assertEquals(Optional.ofNullable(expected), index.typeInfo("pkg.Usage").map(type -> type.superclass));
    }

    @Test
    public void indexedInterfaceLookupPreservesImportAndVisibilityRules() throws Exception {
        var parse = parse();
        var index = WorkspaceTypeIndex.fromParseTrees(List.of(parse), visibleTypes::contains);
        assertEquals(expected == null ? List.of() : List.of(expected), index.typeInfo("pkg.Usage").orElseThrow().interfaces);
    }

    private ParseTask parse() throws Exception {
        var path = folder.newFile("Usage.java").toPath();
        Files.writeString(path, "package pkg; " + imports + " class Usage extends Target implements Target {}");
        var parser = Parser.parseJavaFileObject(new SourceFileObject(path));
        return new ParseTask(parser.task, parser.root, parser.hasSyntaxErrors);
    }
}
