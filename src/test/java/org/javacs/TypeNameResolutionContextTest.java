package org.javacs;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.resolve.TypeNames;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TypeNameResolutionContextTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void nonTypeInputsDoNotProbeVisibleTypes() throws Exception {
        var parse = parse("Usage.java", "import other.Target; class Usage {}");
        var probes = new AtomicInteger();
        for (var name : new String[] {null, "", " ", "value"}) {
            assertEquals(Optional.empty(), TypeNames.resolveSimpleName(name, parse.root(), type -> {
                probes.incrementAndGet();
                return true;
            }));
        }
        assertEquals(0, probes.get());
    }

    @Test
    public void defaultPackageCanUseExplicitImports() throws Exception {
        var parse = parse("Usage.java", "import other.Target; class Usage extends Target {}");
        var known = Set.of("other.Target");
        assertEquals(Optional.of("other.Target"), TypeNames.resolveSimpleName("Target", parse.root(), known::contains));
    }

    @Test
    public void importedParameterIdentityDeduplicatesInheritedMethod() throws Exception {
        var target = parse("Target.java", "package other; public class Target {}");
        var base = parse("Base.java", "package pkg; interface Base { void accept(other.Target target); }");
        var usage = parse("Usage.java", "package pkg; import other.Target; class Usage implements Base { public void accept(Target target) {} }");
        var index = WorkspaceTypeIndex.fromParseTrees(List.of(target, base, usage));
        var methods = index.members("pkg.Usage", false).stream().filter(member -> member.name.equals("accept")).toList();
        assertEquals(1, methods.size());
        assertEquals("pkg.Usage", methods.getFirst().ownerType);
    }

    @Test
    public void arrayComponentNormalizationRemainsUnchanged() {
        assertEquals("other.Target", TypeNames.normalize("other.Target[][]"));
        assertEquals("List", TypeNames.normalize("List<String>[]"));
    }

    private ParseTask parse(String filename, String source) throws Exception {
        var path = folder.newFile(filename).toPath();
        Files.writeString(path, source);
        var parser = Parser.parseJavaFileObject(new SourceFileObject(path));
        return new ParseTask(parser.task, parser.root, parser.hasSyntaxErrors);
    }
}
