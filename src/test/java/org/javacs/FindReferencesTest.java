package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.javacs.index.TypeIndexRouter;
import org.javacs.index.ExternalBinaryTypeIndex;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.provider.DefinitionProvider;
import org.javacs.provider.ReferenceProvider;
import org.junit.Test;

public class FindReferencesTest {
    private static final ReferenceContext REFERENCES = referenceContext();

    protected List<String> items(String file, int row, int column) {
        var path = FindResource.path(file);
        var locations = new ReferenceProvider(REFERENCES.compiler, REFERENCES.index, path, row, column, false).find();
        var strings = new ArrayList<String>();
        for (var l : locations) {
            var fileName = StringSearch.fileName(l.uri);
            var line = l.range.start.line;
            strings.add(String.format("%s(%d)", fileName, line + 1));
        }
        return strings;
    }

    protected List<String> itemsWithDeclaration(String file, int row, int column) {
        var path = FindResource.path(file);
        var locations = new ReferenceProvider(REFERENCES.compiler, REFERENCES.index, path, row, column, true).find();
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
        assertThat(
                items("/org/javacs/example/GotoImplementation.java", 9, 21),
                contains("GotoImplementation.java(5)", "GotoImplementation.java(14)"));
    }

    @Test
    public void findConstructorReferences() {
        assertThat(items("/org/javacs/example/ConstructorRefs.java", 4, 10),
                contains("ConstructorRefs.java(9)", "ConstructorRefs.java(10)"));
    }

    @Test
    public void referenceIndirectImport() {
        assertThat(
                items("/org/javacs/other/ImportIndirectly.java", 4, 25), contains("ReferenceIndirectImport.java(9)"));
    }

    @Test
    public void findStackedFieldReferences() {
        var file = "/org/javacs/example/StackedFieldReferences.java";
        assertThat(items(file, 4, 9), hasItem("StackedFieldReferences.java(7)"));
        assertThat(items(file, 4, 12), hasItem("StackedFieldReferences.java(8)"));
        assertThat(items(file, 4, 15), hasItem("StackedFieldReferences.java(9)"));
    }

    @Test
    public void varTypeReferences() {
        assertThat(items("/org/javacs/example/VarTypeReferences.java", 4, 27), contains("VarTypeReferences.java(9)"));
    }

    @Test
    public void staticImportFieldReferences() {
        assertThat(
                items("/org/javacs/example/StaticImportInterface.java", 4, 12),
                contains("GotoStaticImportField.java(7)"));
    }

    @Test
    public void staticImportFieldReferencesFromUsage() {
        assertThat(
                items("/org/javacs/example/GotoStaticImportField.java", 7, 21),
                empty());
    }

    @Test
    public void staticImportFieldReferencesCrossPackage() {
        assertThat(
                items("/org/javacs/example/models/StaticImportCrossPackageInterface.java", 4, 12),
                contains("StaticImportCrossPackageUsage.java(7)"));
    }

    @Test
    public void staticImportMethodReferencesCrossPackage() {
        assertThat(
                items("/org/javacs/example/models/StaticImportCrossPackageInterface.java", 6, 19),
                empty());
    }

    @Test
    public void staticImportMethodReferencesFromUsageCrossPackage() {
        assertThat(
                items("/org/javacs/example/service/StaticImportCrossPackageUsage.java", 11, 16),
                contains("StaticImportCrossPackageUsage.java(11)"));
    }

    @Test
    public void inheritedFieldReferencesFromDeclaration() {
        var file = "/org/javacs/example/InheritedPojoMembers.java";
        // LombokInheritedPojoMembers has a field also named 'inheritedService' but on a different
        // owner type (LombokInheritedPojoBase) — it must not appear when searching InheritedPojoBase#inheritedService.
        assertThat(items(file, 10, 19), contains("InheritedPojoMembers.java(5)"));
    }

    @Test
    public void inheritedFieldReferencesFromUsage() {
        var file = "/org/javacs/example/InheritedPojoMembers.java";
        assertThat(items(file, 5, 10), contains("InheritedPojoMembers.java(5)"));
    }

    @Test
    public void fieldReferencedThroughSubtypeIsFound() {
        // InheritedPojoMembers extends InheritedPojoBase; a usage of the base-class field
        // through a subtype instance must be found when searching from the declaration.
        var file = "/org/javacs/example/InheritedPojoMembers.java";
        assertThat(items(file, 10, 19), hasItem("InheritedPojoMembers.java(5)"));
    }

    @Test
    public void recordComponentReferencesAreScoped() {
        // Cursor on record component 'foo'; must only find references within GotoRecordAccessor,
        // not every occurrence of the name 'foo' across the workspace.
        var file = "/org/javacs/example/GotoRecordAccessor.java";
        assertThat(items(file, 3, 35), contains("GotoRecordAccessor.java(5)"));
    }

    @Test
    public void fieldDeclarationIncludedWhenRequested() {
        // With includeDeclaration=true the field declaration site must appear in results.
        var file = "/org/javacs/example/StackedFieldReferences.java";
        assertThat(itemsWithDeclaration(file, 4, 9),
                hasItems("StackedFieldReferences.java(4)", "StackedFieldReferences.java(7)"));
    }

    @Test
    public void lombokFieldReferencesIncludeGeneratedAccessors() {
        var file = "/org/javacs/example/LombokFieldReferences.java";
        assertThat(
                items(file, 7, 20),
                hasItems(
                        "LombokFieldReferences.java(10)",
                        "LombokFieldReferences.java(11)",
                        "LombokFieldReferences.java(12)",
                        "LombokFieldReferences.java(13)"));
    }


    @Test
    public void abstractMethodReferencesIncludeOverridesAndCallSites() {
        var file = "/org/javacs/example/OverrideHierarchy.java";
        assertThat(
                items(file, 4, 21),
                contains(
                        "OverrideHierarchy.java(9)",
                        "OverrideHierarchy.java(16)",
                        "OverrideHierarchy.java(16)"));
    }

    @Test
    public void interfaceDefaultMethodReferencesIncludeInheritedCallSites() {
        var file = "/org/javacs/example/InterfaceDefaultReference.java";
        assertThat(items(file, 4, 28), contains("InterfaceDefaultReference.java(11)"));
    }

    private static ReferenceContext referenceContext() {
        var workspaceRoot = LanguageServerFixture.DEFAULT_WORKSPACE_ROOT;
        FileStore.reset();
        FileStore.setWorkspaceRoots(Set.of(workspaceRoot));
        var infer = new InferConfig(workspaceRoot);
        var compiler =
                new JavaCompilerService(
                        infer.classPath(), infer.buildDocPath(), java.util.Collections.emptySet(), java.util.Collections.emptySet());
        TypeIndexRouter index;
        try (var task = compiler.compile(FileStore.all().toArray(Path[]::new))) {
            index =
                    new TypeIndexRouter(
                            WorkspaceTypeIndex.from(task),
                            new ExternalBinaryTypeIndex(compiler));
        }
        return new ReferenceContext(compiler, index);
    }

    private static final class ReferenceContext {
        final JavaCompilerService compiler;
        final TypeIndexRouter index;

        ReferenceContext(JavaCompilerService compiler, TypeIndexRouter index) {
            this.compiler = compiler;
            this.index = index;
        }
    }
}
