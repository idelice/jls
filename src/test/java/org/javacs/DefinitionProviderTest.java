package org.javacs;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.tools.JavaFileObject;
import org.javacs.index.ExternalBinaryTypeIndex;
import org.javacs.index.TypeIndexRouter;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.index.IndexedMember;
import org.javacs.index.IndexedType;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.Location;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.provider.DefinitionProvider;
import org.junit.After;
import org.junit.Test;

public class DefinitionProviderTest {
    @Test
    public void indexedOwnerEarlyMethodReturnKeepsIndexedTypeUntilSelectionCompletes() throws Exception {
        var range = new Range(new Position(2, 4), new Position(2, 8));
        var member =
                new IndexedMember(
                        "test.Owner",
                        "call",
                        CompletionItemKind.Method,
                        true,
                        false,
                        false,
                        true,
                        false,
                        0,
                        "call()",
                        "void",
                        "void",
                        new String[0],
                        new String[0],
                        new String[0],
                        IndexedMember.canonicalKey("test.Owner", CompletionItemKind.Method, "call", new String[0]),
                        "test.Owner#call",
                        null,
                        false,
                        IndexedMember.Origin.DECLARED,
                        IndexedMember.Provenance.WORKSPACE,
                        Set.of(),
                        Path.of("Owner.java").toUri(),
                        range,
                        "test.Owner",
                        IndexedMember.canonicalKey("test.Owner", CompletionItemKind.Method, "call", new String[0]));
        var type =
                new IndexedType(
                        "test.Owner",
                        "Owner",
                        List.of(member),
                        false,
                        Path.of("Owner.java"),
                        Path.of("Owner.java").toUri(),
                        null,
                        List.of(),
                        List.of(),
                        CompletionItemKind.Class,
                        Set.of(),
                        null,
                        IndexedMember.Provenance.WORKSPACE);
        var workspace = workspaceIndex(Map.of("test.Owner", type));
        var provider =
                new DefinitionProvider(
                        new NoopCompilerProvider(),
                        new TypeIndexRouter(workspace, ExternalBinaryTypeIndex.EMPTY),
                        Path.of("Dummy.java"),
                        1,
                        1);

        var method =
                DefinitionProvider.class.getDeclaredMethod(
                        "methodEarlyReturn", IndexedType.class, String.class, boolean.class, int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        var resolved =
                (Optional<DefinitionProvider.ResolvedSymbol>)
                        method.invoke(provider, type, "call", true, 0);

        assertThat(resolved.isPresent(), is(true));
        assertThat(resolved.get().locations(), not(empty()));
        assertThat(resolved.get().qualifiedType(), is("test.Owner"));
        assertThat(resolved.get().indexMember(), is(member));
    }

    @Test
    public void strictIndexedMethodTreatsUnsupportedFallbackAsAbsent() throws Exception {
        var member =
                new IndexedMember(
                        "test.Owner",
                        "call",
                        CompletionItemKind.Method,
                        false,
                        false,
                        0,
                        "call()",
                        "void",
                        new String[0],
                        new String[0],
                        IndexedMember.canonicalKey("test.Owner", CompletionItemKind.Method, "call", new String[0]),
                        "test.Owner#call",
                        null,
                        false);
        var type =
                new IndexedType(
                        "test.Owner",
                        "Owner",
                        List.of(member),
                        false,
                        null,
                        null,
                        List.of(),
                        IndexedMember.Provenance.WORKSPACE);
        var workspace = workspaceIndex(Map.of("test.Owner", type));
        var provider =
                new DefinitionProvider(
                        new NoopCompilerProvider(),
                        new TypeIndexRouter(workspace, ExternalBinaryTypeIndex.EMPTY),
                        Path.of("Dummy.java"),
                        1,
                        1);

        var strictIndexedMethod =
                DefinitionProvider.class.getDeclaredMethod(
                        "strictIndexedMethod", String.class, String.class, boolean.class, int.class);
        strictIndexedMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Optional<DefinitionProvider.ResolvedSymbol> resolved =
                (Optional<DefinitionProvider.ResolvedSymbol>)
                        strictIndexedMethod.invoke(provider, "test.Owner", "call", false, 0);

        assertThat("unsupported indexed fallback must not be treated as a successful strict resolution", resolved.isPresent(), is(false));
    }

    @Test
    public void unqualifiedCurrentOwnerSelectionUsesIndexBeforeHeavyMaterialization() throws Exception {
        var workspace = Files.createTempDirectory("definition-provider-current-owner");
        try {
            var appDir = workspace.resolve("app");
            Files.createDirectories(appDir);
            var use = appDir.resolve("Use.java");
            Files.writeString(
                    use,
                    "package app;\n"
                            + "class Use {\n"
                            + "  String helper(String input) { return input; }\n"
                            + "  void test() { helper(\"x\"); }\n"
                            + "}\n");

            FileStore.setWorkspaceRoots(Set.of(workspace));
            var service =
                    new JavaCompilerService(
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());
            var tracking = new TrackingCompilerProvider(service);
            var index = workspaceIndex(service, use);
            var provider = new DefinitionProvider(tracking, index, use, 1, 1);
            var parse = service.parse(use);

            var select =
                    DefinitionProvider.class.getDeclaredMethod(
                            "selectUnqualifiedMethodSymbol",
                            com.sun.source.tree.CompilationUnitTree.class,
                            Optional.class,
                            String.class,
                            int.class,
                            List.class);
            select.setAccessible(true);
            var selected =
                    (Optional<?>)
                            select.invoke(
                                    provider,
                                    parse.root(),
                                    Optional.of("app.Use"),
                                    "helper",
                                    1,
                                    List.of("java.lang.String"));

            assertThat(selected.isPresent(), is(true));
            assertThat("selection should stay cheap", tracking.parseCount, is(0));

            var resolveIndexed = resolveIndexedMethodResult();
            var selectedMethod = selected.get();
            var ownerType = selectedMethod.getClass().getDeclaredMethod("ownerType");
            var methodName = selectedMethod.getClass().getDeclaredMethod("methodName");
            var member = selectedMethod.getClass().getDeclaredMethod("member");
            ownerType.setAccessible(true);
            methodName.setAccessible(true);
            member.setAccessible(true);
            @SuppressWarnings("unchecked")
            var resolved =
                    (Optional<DefinitionProvider.ResolvedSymbol>)
                            resolveIndexed.invoke(
                                    provider,
                                    ownerType.invoke(selectedMethod),
                                    methodName.invoke(selectedMethod),
                                    member.invoke(selectedMethod));

            assertThat(resolved.isPresent(), is(true));
            assertThat(resolved.get().locations(), not(empty()));
            assertThat("indexed resolution should avoid extra parse work", tracking.parseCount, is(0));
        } finally {
            deleteTree(workspace);
        }
    }

    @Test
    public void unqualifiedMethodSkipsArgumentTypeResolutionWhenAritySelectionIsEnough() throws Exception {
        var workspace = Files.createTempDirectory("definition-provider-unqualified-lazy-args");
        try {
            var appDir = workspace.resolve("app");
            Files.createDirectories(appDir);
            var use = appDir.resolve("Use.java");
            Files.writeString(
                    use,
                    "package app;\n"
                            + "class Use {\n"
                            + "  String helper(String input) { return input; }\n"
                            + "  void test() { helper(\"x\".trim()); }\n"
                            + "}\n");

            FileStore.setWorkspaceRoots(Set.of(workspace));
            var service =
                    new JavaCompilerService(
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());
            var tracking = new TrackingCompilerProvider(service);
            var index = workspaceIndex(service, use);
            var locations = new DefinitionProvider(tracking, index, use, 4, 17).find();

            assertThat(locations, not(empty()));
            assertThat(
                    "unique arity selection should return before argument type resolution sets up external reflection",
                    tracking.classPathRootsCount,
                    is(0));
        } finally {
            deleteTree(workspace);
        }
    }

    @Test
    public void staticImportSelectionDetectsAmbiguityWithoutHeavyWork() throws Exception {
        var workspace = Files.createTempDirectory("definition-provider-static-ambiguous");
        try {
            var pDir = workspace.resolve("p");
            var qDir = workspace.resolve("q");
            var appDir = workspace.resolve("app");
            Files.createDirectories(pDir);
            Files.createDirectories(qDir);
            Files.createDirectories(appDir);
            Files.writeString(
                    pDir.resolve("UtilOne.java"),
                    "package p;\npublic class UtilOne { public static String call(String input) { return input; } }\n");
            Files.writeString(
                    qDir.resolve("UtilTwo.java"),
                    "package q;\npublic class UtilTwo { public static String call(String input) { return input; } }\n");
            var use = appDir.resolve("Use.java");
            Files.writeString(
                    use,
                    "package app;\n"
                            + "import static p.UtilOne.call;\n"
                            + "import static q.UtilTwo.call;\n"
                            + "class Use { void test() { call(\"x\"); } }\n");

            FileStore.setWorkspaceRoots(Set.of(workspace));
            var service =
                    new JavaCompilerService(
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());
            var tracking = new TrackingCompilerProvider(service);
            var index = workspaceIndex(service, use, pDir.resolve("UtilOne.java"), qDir.resolve("UtilTwo.java"));
            var provider = new DefinitionProvider(tracking, index, use, 1, 1);
            var parse = service.parse(use);

            var select = selectStaticImportMethodSymbol();
            var selected =
                    (Optional<?>)
                            select.invoke(provider, parse.root(), "call", 1, List.of("java.lang.String"));

            assertThat(selected.isPresent(), is(false));
            assertThat("ambiguity detection should not parse sources", tracking.parseCount, is(0));
            assertThat("ambiguity detection should not probe attached external sources", tracking.findAnywhereCount, is(0));
        } finally {
            deleteTree(workspace);
        }
    }

    @Test
    public void staticImportMaterializationDefersWorkspaceSourceParseUntilAfterSelection() throws Exception {
        var workspace = Files.createTempDirectory("definition-provider-static-materialize");
        try {
            var pDir = workspace.resolve("p");
            var qDir = workspace.resolve("q");
            var appDir = workspace.resolve("app");
            Files.createDirectories(pDir);
            Files.createDirectories(qDir);
            Files.createDirectories(appDir);
            var utilOne = pDir.resolve("UtilOne.java");
            Files.writeString(
                    utilOne,
                    "package p;\npublic class UtilOne { public static String call(String input) { return input; } }\n");
            Files.writeString(
                    qDir.resolve("UtilTwo.java"),
                    "package q;\npublic class UtilTwo { public static String other(String input) { return input; } }\n");
            var use = appDir.resolve("Use.java");
            Files.writeString(
                    use,
                    "package app;\n"
                            + "import static p.UtilOne.call;\n"
                            + "import static q.UtilTwo.*;\n"
                            + "class Use { void test() { call(\"x\"); } }\n");

            FileStore.setWorkspaceRoots(Set.of(workspace));
            var service =
                    new JavaCompilerService(
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());
            var tracking = new TrackingCompilerProvider(service);
            var provider =
                    new DefinitionProvider(
                            tracking,
                            new TypeIndexRouter(
                                    workspaceIndexWithoutMethodLocations(
                                            Map.of(
                                                    "p.UtilOne",
                                                    workspaceTypeWithoutLocations("p.UtilOne", "UtilOne", utilOne, "call"),
                                                    "q.UtilTwo",
                                                    workspaceTypeWithoutLocations("q.UtilTwo", "UtilTwo", qDir.resolve("UtilTwo.java"), "other"))),
                                    ExternalBinaryTypeIndex.EMPTY),
                            use,
                            1,
                            1);
            var parse = service.parse(use);

            var select = selectStaticImportMethodSymbol();
            var selected =
                    (Optional<?>)
                            select.invoke(provider, parse.root(), "call", 1, List.of("java.lang.String"));

            assertThat(selected.isPresent(), is(true));
            assertThat("selection should not parse workspace sources", tracking.parseCount, is(0));

            var materialize = materializeSelectedMethod();
            var resolved = (DefinitionProvider.ResolvedSymbol) materialize.invoke(provider, selected.get());

            assertThat(resolved.locations(), not(empty()));
            assertThat("materialization should parse only after a symbol was selected", tracking.parseCount, is(greaterThanOrEqualTo(1)));
        } finally {
            deleteTree(workspace);
        }
    }

    @Test
    public void innerClassResolvesOuterFieldAndMethodDefinitions() throws Exception {
        var workspace = Files.createTempDirectory("definition-provider-enclosing-owner");
        try {
            var appDir = workspace.resolve("app");
            Files.createDirectories(appDir);
            var use = appDir.resolve("Use.java");
            Files.writeString(
                    use,
                    "package app;\n"
                            + "class Use {\n"
                            + "  int pendingCompletionIndex;\n"
                            + "  void filterJavaFiles() {}\n"
                            + "  class Inner {\n"
                            + "    void test() {\n"
                            + "      pendingCompletionIndex = 1;\n"
                            + "      filterJavaFiles();\n"
                            + "    }\n"
                            + "  }\n"
                            + "}\n");

            FileStore.setWorkspaceRoots(Set.of(workspace));
            var service =
                    new JavaCompilerService(
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());
            var index = workspaceIndex(service, use);

            var fieldCursor = cursor(use, "pendingCompletionIndex = 1");
            var fieldLocations =
                    new DefinitionProvider(service, index, use, fieldCursor.line, fieldCursor.character)
                            .find();
            assertThat(fieldLocations, not(empty()));
            assertThat(fieldLocations.get(0).range.start.line, is(2));

            var methodCursor = cursor(use, "filterJavaFiles();");
            var methodLocations =
                    new DefinitionProvider(service, index, use, methodCursor.line, methodCursor.character)
                            .find();
            assertThat(methodLocations, not(empty()));
            assertThat(methodLocations.get(0).range.start.line, is(3));
        } finally {
            deleteTree(workspace);
        }
    }

    @Test
    public void externalBinaryMethodNavigatesToDecompiledSource() throws Exception {
        // Uses the maven-project fixture (Gson 2.8.9 on classpath).
        var compiler = LanguageServerFixture.getCompilerProvider();
        var use =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT
                        .resolve("src/org/javacs/example/GotoExternalBinaryMethod.java");
        var pos = cursor(use, "toJson");
        var locations =
                new DefinitionProvider(compiler, TypeIndexRouter.EMPTY, use, pos.line, pos.character)
                        .find();
        assertThat("expected non-empty definition for external binary method", locations, not(empty()));
    }

    @Test
    public void jdkMethodNavigatesToDecompiledSource() throws Exception {
        // Uses the maven-project fixture. Navigates to a JDK platform class method (java.util.concurrent.Executors)
        // whose bytecode lives in the JDK module system (jrt:/), not on the Maven classpath.
        var compiler = LanguageServerFixture.getCompilerProvider();
        var use =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT
                        .resolve("src/org/javacs/example/GotoJdkMethod.java");
        var pos = cursor(use, "newSingleThreadExecutor");
        var locations =
                new DefinitionProvider(compiler, TypeIndexRouter.EMPTY, use, pos.line, pos.character)
                        .find();
        assertThat("expected non-empty definition for JDK platform method", locations, not(empty()));
    }

    @Test
    public void lombokGetterNavigatesToBackingField() throws Exception {
        // Sets up FileStore with the maven-project workspace (which has Lombok on classpath).
        var compiler = LanguageServerFixture.getCompilerProvider();
        var use =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT
                        .resolve("src/org/javacs/example/LombokCrossTypeDiagnostics.java");
        // cursor on 'getName' in "model.getName();"
        var pos = cursor(use, "getName");
        var locations =
                new DefinitionProvider(compiler, TypeIndexRouter.EMPTY, use, pos.line, pos.character)
                        .find();
        assertThat(
                "Lombok getter should navigate to the backing field in LombokCrossTypeModel",
                locations,
                not(empty()));
        assertThat(locations.get(0).uri.toString(), containsString("LombokCrossTypeModel"));
    }

    @Test
    public void debugNonLombokMethodDefinition() throws Exception {
        var workspace = Files.createTempDirectory("definition-debug-method");
        try {
            var appDir = workspace.resolve("app");
            Files.createDirectories(appDir);
            var use = appDir.resolve("Use.java");
            Files.writeString(
                    use,
                    "package app;\n"
                            + "class Helper { String greet() { return \"hi\"; } }\n"
                            + "class Use { void test() { new Helper().greet(); } }\n");

            var locations = debugDefinition(workspace, use, "greet();");
            assertThat(locations.size(), greaterThanOrEqualTo(0));
        } finally{
            deleteTree(workspace);
        }
    }

    @Test
    public void debugAbstractMethodDefinition() throws Exception {
        var workspace = Files.createTempDirectory("definition-debug-abstract-method");
        try {
            var appDir = workspace.resolve("app");
            Files.createDirectories(appDir);
            var use = appDir.resolve("Use.java");
            Files.writeString(
                    use,
                    "package app;\n"
                            + "abstract class Base { abstract int endHour(); }\n"
                            + "class Customer extends Base { int endHour() { return 17; } }\n"
                            + "class Use { void test() { Base customer = new Customer(); customer.endHour(); } }\n");

            var locations = debugDefinition(workspace, use, "endHour(); } }");
            assertThat(locations.size(), greaterThanOrEqualTo(0));
        } finally {
            deleteTree(workspace);
        }
    }

    @Test
    public void debugExternalMethodDefinition() throws Exception {
        var workspace = Files.createTempDirectory("definition-debug-external-method");
        try {
            var appDir = workspace.resolve("app");
            Files.createDirectories(appDir);
            var use = appDir.resolve("Use.java");
            Files.writeString(
                    use,
                    "package app;\n"
                            + "class Use { void test() { \" value \".trim(); } }\n");

            var locations = debugDefinition(workspace, use, "trim");
            assertThat(locations.size(), greaterThanOrEqualTo(0));
        } finally {
            deleteTree(workspace);
        }
    }

    @Test
    public void debugEnumDefinition() throws Exception {
        var workspace = Files.createTempDirectory("definition-debug-enum");
        try {
            var appDir = workspace.resolve("app");
            Files.createDirectories(appDir);
            var use = appDir.resolve("Use.java");
            Files.writeString(
                    use,
                    "package app;\n"
                            + "enum Status { READY }\n"
                            + "class Use { void test() { Status status = Status.READY; } }\n");

            var locations = debugDefinition(workspace, use, "READY; } }");
            assertThat(locations.size(), greaterThanOrEqualTo(0));
        } finally {
            deleteTree(workspace);
        }
    }

    @Test
    public void debugClassDefinition() throws Exception {
        var workspace = Files.createTempDirectory("definition-debug-class");
        try {
            var appDir = workspace.resolve("app");
            Files.createDirectories(appDir);
            var use = appDir.resolve("Use.java");
            Files.writeString(
                    use,
                    "package app;\n"
                            + "class Customer {}\n"
                            + "class Use { void test() { new Customer(); } }\n");

            var locations = debugDefinition(workspace, use, "Customer();");
            assertThat(locations.size(), greaterThanOrEqualTo(0));
        } finally {
            deleteTree(workspace);
        }
    }

    @Test
    public void debugRecordAccessorDefinition() throws Exception {
        var workspace = Files.createTempDirectory("definition-debug-record");
        try {
            var appDir = workspace.resolve("app");
            Files.createDirectories(appDir);
            var use = appDir.resolve("Use.java");
            Files.writeString(
                    use,
                    "package app;\n"
                            + "record Person(String name) {}\n"
                            + "class Use { void test() { new Person(\"Ada\").name(); } }\n");

            var locations = debugDefinition(workspace, use, "name();");
            assertThat(locations.size(), greaterThanOrEqualTo(0));
        } finally {
            deleteTree(workspace);
        }
    }

    @Test
    public void debugStaticImportMethodDefinition() throws Exception {
        var workspace = Files.createTempDirectory("definition-debug-static-import");
        try {
            var appDir = workspace.resolve("app");
            Files.createDirectories(appDir);
            var use = appDir.resolve("Use.java");
            Files.writeString(
                    use,
                    "package app;\n"
                            + "import static app.Tools.answer;\n"
                            + "class Tools { static int answer() { return 42; } }\n"
                            + "class Use { void test() { answer(); } }\n");

            var locations = debugDefinition(workspace, use, "answer();");
            assertThat(locations.size(), greaterThanOrEqualTo(0));
        } finally {
            deleteTree(workspace);
        }
    }

    @After
    public void resetWorkspaceRoots() {
        FileStore.setWorkspaceRoots(Collections.emptySet());
    }

    private static WorkspaceTypeIndex workspaceIndex(Map<String, IndexedType> types) throws Exception {
        Constructor<WorkspaceTypeIndex> ctor =
                WorkspaceTypeIndex.class.getDeclaredConstructor(Map.class, Map.class);
        ctor.setAccessible(true);
        return ctor.newInstance(types, Map.of());
    }

    private static TypeIndexRouter workspaceIndex(JavaCompilerService service, Path... files) throws Exception {
        try (var task = service.compile(files)) {
            return new TypeIndexRouter(WorkspaceTypeIndex.from(task), ExternalBinaryTypeIndex.EMPTY);
        }
    }

    private static WorkspaceTypeIndex workspaceIndexWithoutMethodLocations(Map<String, IndexedType> types)
            throws Exception {
        return workspaceIndex(types);
    }

    private static IndexedType workspaceTypeWithoutLocations(
            String qualifiedName, String simpleName, Path sourcePath, String methodName) {
        var member =
                new IndexedMember(
                        qualifiedName,
                        methodName,
                        CompletionItemKind.Method,
                        true,
                        false,
                        0,
                        methodName + "()",
                        "java.lang.String",
                        new String[] {"input"},
                        new String[] {"java.lang.String"},
                        IndexedMember.canonicalKey(
                                qualifiedName, CompletionItemKind.Method, methodName, new String[] {"java.lang.String"}),
                        qualifiedName + "#" + methodName,
                        null,
                        false);
        return new IndexedType(
                qualifiedName,
                simpleName,
                List.of(member),
                false,
                sourcePath,
                sourcePath.toUri(),
                null,
                List.of(),
                List.of(),
                CompletionItemKind.Class,
                Set.of(),
                null,
                IndexedMember.Provenance.WORKSPACE);
    }

    private static Method selectStaticImportMethodSymbol() throws Exception {
        var method =
                DefinitionProvider.class.getDeclaredMethod(
                        "selectStaticImportMethodSymbol",
                        com.sun.source.tree.CompilationUnitTree.class,
                        String.class,
                        int.class,
                        List.class);
        method.setAccessible(true);
        return method;
    }

    private static Method materializeSelectedMethod() throws Exception {
        Class<?> selectedMethodClass = null;
        for (var nested : DefinitionProvider.class.getDeclaredClasses()) {
            if ("SelectedMethod".equals(nested.getSimpleName())) {
                selectedMethodClass = nested;
                break;
            }
        }
        if (selectedMethodClass == null) {
            throw new AssertionError("missing SelectedMethod nested class");
        }
        var method = DefinitionProvider.class.getDeclaredMethod("materializeSelectedMethod", selectedMethodClass);
        method.setAccessible(true);
        return method;
    }

    private static Method resolveIndexedMethodResult() throws Exception {
        var method =
                DefinitionProvider.class.getDeclaredMethod(
                        "resolveIndexedMethodResult", String.class, String.class, IndexedMember.class);
        method.setAccessible(true);
        return method;
    }

    private static Position cursor(Path file, String needle) throws Exception {
        var source = Files.readString(file);
        var offset = source.indexOf(needle);
        if (offset < 0) {
            throw new AssertionError("missing needle: " + needle);
        }
        var line = 0;
        var column = 0;
        for (var i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 0;
            } else {
                column++;
            }
        }
        return new Position(line + 1, column + 1);
    }

    private static List<Location> debugDefinition(Path workspace, Path file, String needle) throws Exception {
        FileStore.setWorkspaceRoots(Set.of(workspace));
        var compiler =
                new JavaCompilerService(
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet());
        var pos = cursor(file, needle);
        return new DefinitionProvider(compiler, TypeIndexRouter.EMPTY, file, pos.line, pos.character)
                .find();
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        var paths = new ArrayList<Path>();
        try (var walk = Files.walk(root)) {
            walk.forEach(paths::add);
        }
        paths.sort((a, b) -> b.compareTo(a));
        for (var path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private static final class NoopCompilerProvider implements CompilerProvider {
        @Override
        public Set<String> imports() {
            return Set.of();
        }

        @Override
        public List<String> publicTopLevelTypes() {
            return List.of();
        }

        @Override
        public List<String> packagePrivateTopLevelTypes(String packageName) {
            return List.of();
        }

        @Override
        public Iterable<Path> search(String query) {
            return List.of();
        }

        @Override
        public Optional<JavaFileObject> findAnywhere(String className) {
            return Optional.empty();
        }

        @Override
        public Path findTypeDeclaration(String className) {
            return NOT_FOUND;
        }

        @Override
        public Path[] findTypeReferences(String className) {
            return new Path[0];
        }

        @Override
        public Path[] findMemberReferences(String className, String memberName) {
            return new Path[0];
        }

        @Override
        public ParseTask parse(Path file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ParseTask parse(JavaFileObject file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompileTask compile(Path... files) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompileTask compile(Collection<? extends JavaFileObject> sources) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TrackingCompilerProvider implements CompilerProvider {
        private final JavaCompilerService delegate;
        private int parseCount;
        private int findAnywhereCount;
        private int classPathRootsCount;

        private TrackingCompilerProvider(JavaCompilerService delegate) {
            this.delegate = delegate;
        }

        @Override
        public Set<String> imports() {
            return delegate.imports();
        }

        @Override
        public List<String> publicTopLevelTypes() {
            return delegate.publicTopLevelTypes();
        }

        @Override
        public Set<Path> classPathRoots() {
            classPathRootsCount++;
            return delegate.classPathRoots();
        }

        @Override
        public List<String> packagePrivateTopLevelTypes(String packageName) {
            return delegate.packagePrivateTopLevelTypes(packageName);
        }

        @Override
        public Iterable<Path> search(String query) {
            return delegate.search(query);
        }

        @Override
        public Optional<JavaFileObject> findAnywhere(String className) {
            findAnywhereCount++;
            return delegate.findAnywhere(className);
        }

        @Override
        public Path findTypeDeclaration(String className) {
            return delegate.findTypeDeclaration(className);
        }

        @Override
        public Path[] findTypeReferences(String className) {
            return delegate.findTypeReferences(className);
        }

        @Override
        public Path[] findMemberReferences(String className, String memberName) {
            return delegate.findMemberReferences(className, memberName);
        }

        @Override
        public ParseTask parse(Path file) {
            parseCount++;
            return delegate.parse(file);
        }

        @Override
        public ParseTask parse(JavaFileObject file) {
            parseCount++;
            return delegate.parse(file);
        }

        @Override
        public CompileTask compile(Path... files) {
            return delegate.compile(files);
        }

        @Override
        public CompileTask compile(Collection<? extends JavaFileObject> sources) {
            return delegate.compile(sources);
        }
    }
}
