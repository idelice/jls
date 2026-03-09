package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.javacs.completion.TypeMemberIndex;
import org.javacs.navigation.ReferenceProvider;
import org.junit.Test;

public class FindReferencesTest {
    private static final ReferenceContext REFERENCES = referenceContext();

    protected List<String> items(String file, int row, int column) {
        var path = FindResource.path(file);
        var locations = new ReferenceProvider(REFERENCES.compiler, REFERENCES.index, path, row, column).find();
        var strings = new ArrayList<String>();
        for (var l : locations) {
            var fileName = StringSearch.fileName(l.uri);
            var line = l.range.start.line;
            strings.add(String.format("%s(%d)", fileName, line + 1));
        }
        return strings;
    }

    @Test
    public void findAllReferences() {
        assertThat(items("/org/javacs/example/GotoOther.java", 6, 30), not(empty()));
    }

    @Test
    public void findInterfaceReference() {
        assertThat(items("/org/javacs/example/GotoImplementation.java", 9, 21), contains("GotoImplementation.java(5)"));
    }

    @Test
    public void findConstructorReferences() {
        assertThat(items("/org/javacs/example/ConstructorRefs.java", 4, 10), contains("ConstructorRefs.java(9)"));
    }

    @Test
    public void referenceIndirectImport() {
        assertThat(
                items("/org/javacs/other/ImportIndirectly.java", 4, 25), contains("ReferenceIndirectImport.java(9)"));
    }

    @Test
    public void findStackedFieldReferences() {
        var file = "/org/javacs/example/StackedFieldReferences.java";
        assertThat(items(file, 4, 9), contains("StackedFieldReferences.java(7)"));
        assertThat(items(file, 4, 12), contains("StackedFieldReferences.java(8)"));
        assertThat(items(file, 4, 15), contains("StackedFieldReferences.java(9)"));
    }

    @Test
    public void varTypeReferences() {
        assertThat(items("/org/javacs/example/VarTypeReferences.java", 4, 27), contains("VarTypeReferences.java(9)"));
    }

    private static ReferenceContext referenceContext() {
        var workspaceRoot = LanguageServerFixture.DEFAULT_WORKSPACE_ROOT;
        FileStore.reset();
        FileStore.setWorkspaceRoots(Set.of(workspaceRoot));
        var infer = new InferConfig(workspaceRoot);
        var compiler =
                new JavaCompilerService(
                        infer.classPath(), infer.buildDocPath(), java.util.Collections.emptySet(), java.util.Collections.emptySet());
        TypeMemberIndex index;
        try (var task = compiler.compile(FileStore.all().toArray(Path[]::new))) {
            index = TypeMemberIndex.from(task);
        }
        return new ReferenceContext(compiler, index);
    }

    private static final class ReferenceContext {
        final JavaCompilerService compiler;
        final TypeMemberIndex index;

        ReferenceContext(JavaCompilerService compiler, TypeMemberIndex index) {
            this.compiler = compiler;
            this.index = index;
        }
    }
}
