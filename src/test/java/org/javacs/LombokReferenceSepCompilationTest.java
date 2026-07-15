package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.javacs.index.TypeIndexRouter;
import org.javacs.index.ExternalBinaryTypeIndex;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.navigation.FindReferences;
import org.javacs.navigation.NavigationHelper;
import org.javacs.provider.ReferenceProvider;
import org.junit.Test;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import javax.lang.model.element.Element;

public class LombokReferenceSepCompilationTest {
    // private static final ReferenceContext REFERENCES = referenceContext();
    //
    // protected List<String> items(String file, int row, int column) {
    //     var path = FindResource.path(file);
    //     var locations = new ReferenceProvider(REFERENCES.compiler, path, row, column).find();
    //     var strings = new ArrayList<String>();
    //     for (var l : locations) {
    //         var fileName = StringSearch.fileName(l.uri);
    //         var line = l.range.start.line;
    //         strings.add(String.format("%s(%d)", fileName, line + 1));
    //     }
    //     return strings;
    // }
    //
    // private static ReferenceContext referenceContext() {
    //     var workspaceRoot = LanguageServerFixture.DEFAULT_WORKSPACE_ROOT;
    //     FileStore.reset();
    //     FileStore.setWorkspaceRoots(Set.of(workspaceRoot));
    //     var infer = new InferConfig(workspaceRoot, Collections.EMPTY_LIST);
    //     var compiler =
    //             new JavaCompilerService(
    //                     infer.classPath(), infer.buildDocPath(), java.util.Collections.emptySet(), java.util.Collections.emptySet());
    //     TypeIndexRouter index;
    //     try (var task = compiler.compile(FileStore.all().toArray(Path[]::new))) {
    //         index =
    //                 new TypeIndexRouter(
    //                         WorkspaceTypeIndex.from(task),
    //                         new ExternalBinaryTypeIndex(compiler));
    //     }
    //     return new ReferenceContext(compiler, index);
    // }
    //
    // @Test
    // public void plainImplIsFoundInSameBatch() {
    //     var file = "/org/javacs/lombokrefsep/SepInterface.java";
    //     assertThat(items(file, 4, 20), hasItem("PlainImpl.java(5)"));
    // }
    //
    // @Test
    // public void slf4jImplIsFoundInSameBatch() {
    //     var file = "/org/javacs/lombokrefsep/SepInterface.java";
    //     assertThat(items(file, 4, 20), hasItem("Slf4jImpl.java(8)"));
    // }
    //
    // @Test
    // public void dataImplIsFoundInSameBatch() {
    //     var file = "/org/javacs/lombokrefsep/SepInterface.java";
    //     assertThat(items(file, 4, 20), hasItem("DataImpl.java(10)"));
    // }
    //
    // @Test
    // public void findsReferencesInSeparateCompilationContext() {
    //     // This test simulates the scenario where the interface and implementor
    //     // files compile in separate javac Contexts — testing Element identity
    //     // across compilation boundaries.
    //     //
    //     // Step 1: Compile the interface file alone → get the target Element
    //     var interfaceFile = FindResource.path("/org/javacs/lombokrefsep/SepInterface.java");
    //
    //     javax.lang.model.element.Element elementFromFirstCompile;
    //     try (var task1 = REFERENCES.compiler.compile(interfaceFile)) {
    //         elementFromFirstCompile = NavigationHelper.findElement(task1, interfaceFile, 4, 20);
    //     }
    //
    //     // Step 2: Compile the Slf4j implementor alone → scan with cross-batch Element
    //     var slf4jFile = FindResource.path("/org/javacs/lombokrefsep/Slf4jImpl.java");
    //     try (var task2 = REFERENCES.compiler.compile(slf4jFile)) {
    //         var paths = new java.util.ArrayList<TreePath>();
    //         var scanner = new FindReferences(task2.trees, elementFromFirstCompile);
    //         for (var root : task2.roots) {
    //             scanner.scan(root, paths);
    //         }
    //
    //         // If cross-context Element identity fails, foundPaths will be empty
    //         assertThat("Cross-compilation FindReferences should find Lombok callers",
    //             paths, is(not(empty())));
    //     }
    // }
    //
    // @Test
    // public void chainedDefaultMethodFoundInPlainCaller() {
    //     var file = "/org/javacs/lombokrefsep/HasDefaultVoid.java";
    //     assertThat(items(file, 4, 20), hasItem("PlainChainedCaller.java(5)"));
    // }
    //
    // @Test
    // public void chainedDefaultMethodFoundInSlf4jCaller() {
    //     var file = "/org/javacs/lombokrefsep/HasDefaultVoid.java";
    //     assertThat(items(file, 4, 20), hasItem("Slf4jChainedCaller.java(8)"));
    // }
    //
    // @Test
    // public void chainedNonDefaultMethodFoundInSlf4jCaller() {
    //     var file = "/org/javacs/lombokrefsep/HasDefaultVoid.java";
    //     assertThat(items(file, 8, 20), hasItem("Slf4jChainedCaller.java(8)"));
    // }
    //
    // private static final class ReferenceContext {
    //     final JavaCompilerService compiler;
    //     final TypeIndexRouter index;
    //
    //     ReferenceContext(JavaCompilerService compiler, TypeIndexRouter index) {
    //         this.compiler = compiler;
    //         this.index = index;
    //     }
    // }
}
