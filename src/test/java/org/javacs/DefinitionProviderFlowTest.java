package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.LogRecord;
import org.javacs.index.TypeIndexRouter;
import org.javacs.index.ExternalBinaryTypeIndex;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.index.IndexedMember;
import org.javacs.provider.DefinitionProvider;
import org.javacs.lsp.Location;
import org.junit.Test;

public class DefinitionProviderFlowTest {
    private static final NavigationContext NAVIGATION = navigationContext();

    @Test
    public void resolvesLocalVariableFromIdentifierUse() throws Exception {
        assertThat(
                gotoDefinition("/org/javacs/example/SymbolUnderCursor.java", "localVariable = field;", 0),
                hasItem("SymbolUnderCursor.java:7"));
    }

    @Test
    public void resolvesWorkspaceFieldFromBareIdentifier() throws Exception {
        assertThat(
                gotoDefinition(
                        "/org/javacs/example/SymbolUnderCursor.java",
                        "localVariable = field;",
                        "localVariable = ".length()),
                hasItem("SymbolUnderCursor.java:4"));
    }

    @Test
    public void resolvesWorkspaceMethodFromUnqualifiedInvocation() throws Exception {
        assertThat(
                gotoDefinition(
                        "/org/javacs/example/SymbolUnderCursor.java",
                        "method(SymbolUnderCursor.class.getName())",
                        0),
                hasItem("SymbolUnderCursor.java:6"));
    }

    @Test
    public void resolvesWorkspaceFieldFromMemberSelect() throws Exception {
        assertThat(
                gotoDefinition("/org/javacs/example/Goto.java", "other.field;", "other.".length()),
                hasItem("GotoOther.java:5"));
    }

    @Test
    public void resolvesWorkspaceMethodFromMemberSelect() throws Exception {
        assertThat(
                gotoDefinition("/org/javacs/example/Goto.java", "other.method()", "other.".length()),
                hasItem("GotoOther.java:9"));
    }

    @Test
    public void compilerLookupResolvesDemoProjectLocalVariableAfterFullCompile() throws Exception {
        var workspace = Path.of(System.getProperty("user.home"), "projects", "demo");
        org.junit.Assume.assumeTrue("demo project must exist for local debug coverage", Files.exists(workspace));
        var file = workspace.resolve("src/main/java/com/example/demo/service/ServiceOne.java");
        org.junit.Assume.assumeTrue("ServiceOne.java must exist in demo project", Files.exists(file));

        FileStore.reset();
        FileStore.setWorkspaceRoots(Set.of(workspace));
        var infer = new InferConfig(workspace);
        var compiler =
                new JavaCompilerService(
                        new LinkedHashSet<Path>(infer.classPath()),
                        infer.buildDocPath(),
                        java.util.Collections.emptySet(),
                        java.util.Collections.emptySet());

        var initialCompile = compiler.compileFastWithProcessors(FileStore.all().toArray(Path[]::new));
        try {
            var index =
                    new TypeIndexRouter(
                            WorkspaceTypeIndex.from(initialCompile),
                            new ExternalBinaryTypeIndex(compiler));
            var cursor = cursor(file, "foo.getBar().getAsd();", 0);

            var locations =
                    new DefinitionProvider(compiler, index, file, cursor.line, cursor.character)
                            .find();

            assertEquals(1, locations.size());
            assertEquals(file.toUri(), locations.get(0).uri);
            assertEquals(14, locations.get(0).range.start.line);
            assertEquals(8, locations.get(0).range.start.character);
            assertEquals(14, locations.get(0).range.end.line);
            assertEquals(11, locations.get(0).range.end.character);
        } finally {
            initialCompile.close();
            FileStore.reset();
        }
    }

    @Test
    public void compilerLookupResolvesDemoProjectConstructorAfterFullCompile() throws Exception {
        var workspace = Path.of(System.getProperty("user.home"), "projects", "demo");
        org.junit.Assume.assumeTrue("demo project must exist for local debug coverage", Files.exists(workspace));
        var file = workspace.resolve("src/main/java/com/example/demo/service/ServiceTwo.java");
        var constructorFile = workspace.resolve("src/main/java/com/example/demo/models/Biz.java");
        org.junit.Assume.assumeTrue("ServiceTwo.java must exist in demo project", Files.exists(file));
        org.junit.Assume.assumeTrue("Biz.java must exist in demo project", Files.exists(constructorFile));

        FileStore.reset();
        FileStore.setWorkspaceRoots(Set.of(workspace));
        var infer = new InferConfig(workspace);
        var compiler =
                new JavaCompilerService(
                        new LinkedHashSet<Path>(infer.classPath()),
                        infer.buildDocPath(),
                        java.util.Collections.emptySet(),
                        java.util.Collections.emptySet());

        var initialCompile = compiler.compileFastWithProcessors(FileStore.all().toArray(Path[]::new));
        try {
            var index =
                    new TypeIndexRouter(
                            WorkspaceTypeIndex.from(initialCompile),
                            new ExternalBinaryTypeIndex(compiler));
            var cursor = cursor(file, "new Biz(\"asd\")", "new ".length());

            var locations =
                    new DefinitionProvider(compiler, index, file, cursor.line, cursor.character)
                            .find();

            assertEquals(1, locations.size());
            assertEquals(constructorFile.toUri(), locations.get(0).uri);
            assertEquals(13, locations.get(0).range.start.line);
            assertEquals(9, locations.get(0).range.start.character);
            assertEquals(13, locations.get(0).range.end.line);
            assertEquals(12, locations.get(0).range.end.character);
        } finally {
            initialCompile.close();
            FileStore.reset();
        }
    }

    @Test
    public void compilerLookupResolvesDemoProjectNestedClassAfterFullCompile() throws Exception {
        var workspace = Path.of(System.getProperty("user.home"), "projects", "demo");
        org.junit.Assume.assumeTrue("demo project must exist for local debug coverage", Files.exists(workspace));
        var file = workspace.resolve("src/main/java/com/example/demo/complex/service/ComplexScenarioService.java");
        var nestedFile = workspace.resolve("src/main/java/com/example/demo/complex/model/DeepGraph.java");
        org.junit.Assume.assumeTrue(
                "ComplexScenarioService.java must exist in demo project", Files.exists(file));
        org.junit.Assume.assumeTrue("DeepGraph.java must exist in demo project", Files.exists(nestedFile));

        FileStore.reset();
        FileStore.setWorkspaceRoots(Set.of(workspace));
        var infer = new InferConfig(workspace);
        var compiler =
                new JavaCompilerService(
                        new LinkedHashSet<Path>(infer.classPath()),
                        infer.buildDocPath(),
                        java.util.Collections.emptySet(),
                        java.util.Collections.emptySet());

        var initialCompile = compiler.compileFastWithProcessors(FileStore.all().toArray(Path[]::new));
        try {
            var index =
                    new TypeIndexRouter(
                            WorkspaceTypeIndex.from(initialCompile),
                            new ExternalBinaryTypeIndex(compiler));
            var cursor = cursor(file, "DeepGraph.DeepNode createLeaf", "DeepGraph.".length());

            var locations =
                    new DefinitionProvider(compiler, index, file, cursor.line, cursor.character)
                            .find();

            assertEquals(1, locations.size());
            assertEquals(nestedFile.toUri(), locations.get(0).uri);
            assertEquals(21, locations.get(0).range.start.line);
            assertEquals(22, locations.get(0).range.start.character);
            assertEquals(21, locations.get(0).range.end.line);
            assertEquals(30, locations.get(0).range.end.character);
        } finally {
            initialCompile.close();
            FileStore.reset();
        }
    }

    @Test
    public void compilerLookupResolvesDemoProjectFieldAfterFullCompile() throws Exception {
        var workspace = Path.of(System.getProperty("user.home"), "projects", "demo");
        org.junit.Assume.assumeTrue("demo project must exist for local debug coverage", Files.exists(workspace));
        var file = workspace.resolve("src/main/java/com/example/demo/service/ServiceOne.java");
        var fieldFile = workspace.resolve("src/main/java/com/example/demo/models/B.java");
        org.junit.Assume.assumeTrue("ServiceOne.java must exist in demo project", Files.exists(file));
        org.junit.Assume.assumeTrue("B.java must exist in demo project", Files.exists(fieldFile));

        FileStore.reset();
        FileStore.setWorkspaceRoots(Set.of(workspace));
        var infer = new InferConfig(workspace);
        var compiler =
                new JavaCompilerService(
                        new LinkedHashSet<Path>(infer.classPath()),
                        infer.buildDocPath(),
                        java.util.Collections.emptySet(),
                        java.util.Collections.emptySet());

        var initialCompile = compiler.compileFastWithProcessors(FileStore.all().toArray(Path[]::new));
        try {
            var index =
                    new TypeIndexRouter(
                            WorkspaceTypeIndex.from(initialCompile),
                            new ExternalBinaryTypeIndex(compiler));
            var cursor = cursor(file, "A.IM_B", "A.".length());

            var locations =
                    new DefinitionProvider(compiler, index, file, cursor.line, cursor.character)
                            .find();

            assertEquals(1, locations.size());
            assertEquals(fieldFile.toUri(), locations.get(0).uri);
            assertEquals(6, locations.get(0).range.start.line);
            assertEquals(29, locations.get(0).range.start.character);
            assertEquals(6, locations.get(0).range.end.line);
            assertEquals(33, locations.get(0).range.end.character);
        } finally {
            initialCompile.close();
            FileStore.reset();
        }
    }

    @Test
    public void compilerLookupResolvesDemoProjectLombokGeneratedFieldsAfterFullCompile()
            throws Exception {
        var workspace = Path.of(System.getProperty("user.home"), "projects", "demo");
        org.junit.Assume.assumeTrue("demo project must exist for local debug coverage", Files.exists(workspace));
        var service = workspace.resolve("src/main/java/com/example/demo/complex/service/ComplexScenarioService.java");
        var customer = workspace.resolve("src/main/java/com/example/demo/complex/model/CustomerProfile.java");
        var abstractCustomer =
                workspace.resolve("src/main/java/com/example/demo/complex/model/AbstractCustomerRecord.java");
        var envelope = workspace.resolve("src/main/java/com/example/demo/complex/model/OrderEnvelope.java");
        var lineItem = workspace.resolve("src/main/java/com/example/demo/complex/model/LineItem.java");
        org.junit.Assume.assumeTrue("ComplexScenarioService.java must exist", Files.exists(service));
        org.junit.Assume.assumeTrue("CustomerProfile.java must exist", Files.exists(customer));
        org.junit.Assume.assumeTrue("AbstractCustomerRecord.java must exist", Files.exists(abstractCustomer));
        org.junit.Assume.assumeTrue("OrderEnvelope.java must exist", Files.exists(envelope));
        org.junit.Assume.assumeTrue("LineItem.java must exist", Files.exists(lineItem));

        FileStore.reset();
        FileStore.setWorkspaceRoots(Set.of(workspace));
        var infer = new InferConfig(workspace);
        var compiler =
                new JavaCompilerService(
                        new LinkedHashSet<Path>(infer.classPath()),
                        infer.buildDocPath(),
                        java.util.Collections.emptySet(),
                        java.util.Collections.emptySet());

        var initialCompile = compiler.compileFastWithProcessors(FileStore.all().toArray(Path[]::new));
        try {
            var context =
                    new NavigationContext(
                            compiler,
                            new TypeIndexRouter(
                                    WorkspaceTypeIndex.from(initialCompile),
                                    new ExternalBinaryTypeIndex(compiler)));

            assertLocation(context, service, "customer.getLoyaltyTier().isBlank()", "customer.".length(),
                    customer, 12, 17, 12, 28);
            assertLocation(context, service, "customer.setContactWindow(new ContactWindow())", "customer.".length(),
                    abstractCustomer, 12, 24, 12, 37);
            assertLocation(context, service, "customer.setVip(seed.length() % 3 == 0)", "customer.".length(),
                    customer, 13, 18, 13, 21);
            assertLocation(context, service, "envelope.setRequestedShipDate", "envelope.".length(),
                    envelope, 17, 20, 17, 37);
            assertLocation(context, service, "envelope.setCustomer(customer)", "envelope.".length(),
                    envelope, 18, 26, 18, 34);

            var builderChain = "LineItem.builder().family(null).quantity(0).sku(null).build()";
            assertLocation(context, service, builderChain, "LineItem.builder().".length(),
                    lineItem, 16, 17, 16, 23);
            assertLocation(context, service, builderChain, "LineItem.builder().family(null).".length(),
                    lineItem, 17, 14, 17, 22);
            assertLocation(context, service, builderChain, "LineItem.builder().family(null).quantity(0).".length(),
                    lineItem, 15, 17, 15, 20);
        } finally {
            initialCompile.close();
            FileStore.reset();
        }
    }

    @Test
    public void compilerLookupResolvesEnumCasesAfterFullCompile() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-enum-compiler");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);

            var enumFile = pkg.resolve("WorkflowState.java");
            Files.writeString(
                    enumFile,
                    "package com.example.demo;\n"
                            + "enum WorkflowState {\n"
                            + "  DRAFT,\n"
                            + "  ACTIVE(\"active\"),\n"
                            + "  CLOSED(\"closed\", true);\n"
                            + "  private String label;\n"
                            + "  private final boolean terminal;\n"
                            + "  WorkflowState() {\n"
                            + "    this(\"draft\", false);\n"
                            + "  }\n"
                            + "  WorkflowState(String label) {\n"
                            + "    this(label, false);\n"
                            + "  }\n"
                            + "  WorkflowState(String label, boolean terminal) {\n"
                            + "    this.label = label;\n"
                            + "    this.terminal = terminal;\n"
                            + "  }\n"
                            + "  String getLabel() {\n"
                            + "    return label;\n"
                            + "  }\n"
                            + "  void setLabel(String label) {\n"
                            + "    this.label = label;\n"
                            + "  }\n"
                            + "  boolean isTerminal() {\n"
                            + "    return terminal;\n"
                            + "  }\n"
                            + "}\n");
            var use = pkg.resolve("WorkflowUse.java");
            Files.writeString(
                    use,
                    "package com.example.demo;\n"
                            + "class WorkflowUse {\n"
                            + "  void test() {\n"
                            + "    WorkflowState state = WorkflowState.ACTIVE;\n"
                            + "    WorkflowState closed = WorkflowState.CLOSED;\n"
                            + "    state.getLabel();\n"
                            + "    state.setLabel(\"ready\");\n"
                            + "    closed.isTerminal();\n"
                            + "  }\n"
                            + "}\n");

            FileStore.reset();
            FileStore.setWorkspaceRoots(Set.of(workspace));
            var infer = new InferConfig(workspace);
            var compiler =
                    new JavaCompilerService(
                            new LinkedHashSet<Path>(infer.classPath()),
                            infer.buildDocPath(),
                            java.util.Collections.emptySet(),
                            java.util.Collections.emptySet());

            var initialCompile = compiler.compileFastWithProcessors(FileStore.all().toArray(Path[]::new));
            try {
                var context =
                        new NavigationContext(
                                compiler,
                                new TypeIndexRouter(
                                        WorkspaceTypeIndex.from(initialCompile),
                                        new ExternalBinaryTypeIndex(compiler)));

                assertLocation(context, use, "WorkflowState state", 0, enumFile, 1, 5, 1, 18);
                assertLocation(
                        context,
                        use,
                        "WorkflowState.ACTIVE",
                        "WorkflowState.".length(),
                        enumFile,
                        3,
                        2,
                        3,
                        8);
                assertLocation(
                        context,
                        use,
                        "WorkflowState.CLOSED",
                        "WorkflowState.".length(),
                        enumFile,
                        4,
                        2,
                        4,
                        8);
                assertLocation(context, enumFile, "WorkflowState() {", 0, enumFile, 7, 2, 7, 15);
                assertLocation(
                        context,
                        enumFile,
                        "WorkflowState(String label) {",
                        0,
                        enumFile,
                        10,
                        2,
                        10,
                        15);
                assertLocation(
                        context,
                        enumFile,
                        "WorkflowState(String label, boolean terminal)",
                        0,
                        enumFile,
                        13,
                        2,
                        13,
                        15);
                assertLocation(
                        context,
                        use,
                        "state.getLabel()",
                        "state.".length(),
                        enumFile,
                        17,
                        9,
                        17,
                        17);
                assertLocation(
                        context,
                        use,
                        "state.setLabel",
                        "state.".length(),
                        enumFile,
                        20,
                        7,
                        20,
                        15);
                assertLocation(
                        context,
                        use,
                        "closed.isTerminal()",
                        "closed.".length(),
                        enumFile,
                        23,
                        10,
                        23,
                        20);
                assertLocation(
                        context,
                        enumFile,
                        "this.label = label;",
                        "this.".length(),
                        enumFile,
                        5,
                        17,
                        5,
                        22);
                assertLocation(
                        context,
                        enumFile,
                        "this.terminal = terminal;",
                        "this.".length(),
                        enumFile,
                        6,
                        24,
                        6,
                        32);
                assertLocation(
                        context,
                        enumFile,
                        "return label;",
                        "return ".length(),
                        enumFile,
                        5,
                        17,
                        5,
                        22);
            } finally {
                initialCompile.close();
            }
        } finally {
            FileStore.reset();
            deleteRecursively(workspace);
        }
    }

    @Test
    public void compilerLookupResolvesMethodCasesAfterFullCompile() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-method-compiler");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);

            var model = pkg.resolve("MethodModel.java");
            Files.writeString(
                    model,
                    "package com.example.demo;\n"
                            + "class MethodBase {\n"
                            + "  String inheritedName() {\n"
                            + "    return \"base\";\n"
                            + "  }\n"
                            + "}\n"
                            + "class MethodModel extends MethodBase {\n"
                            + "  private String name;\n"
                            + "  String getName() {\n"
                            + "    return name;\n"
                            + "  }\n"
                            + "  void setName(String name) {\n"
                            + "    this.name = name;\n"
                            + "  }\n"
                            + "  String label() {\n"
                            + "    return getName();\n"
                            + "  }\n"
                            + "  String label(String prefix) {\n"
                            + "    return prefix + name;\n"
                            + "  }\n"
                            + "  static MethodModel create() {\n"
                            + "    return new MethodModel();\n"
                            + "  }\n"
                            + "  static MethodModel create(String name) {\n"
                            + "    var model = new MethodModel();\n"
                            + "    model.setName(name);\n"
                            + "    return model;\n"
                            + "  }\n"
                            + "}\n");
            var use = pkg.resolve("MethodUse.java");
            Files.writeString(
                    use,
                    "package com.example.demo;\n"
                            + "class MethodUse {\n"
                            + "  void test() {\n"
                            + "    var model = MethodModel.create(\"Ada\");\n"
                            + "    MethodModel.create();\n"
                            + "    model.getName();\n"
                            + "    model.setName(\"Grace\");\n"
                            + "    model.label();\n"
                            + "    model.label(\"Dr.\");\n"
                            + "    model.inheritedName();\n"
                            + "  }\n"
                            + "}\n");

            FileStore.reset();
            FileStore.setWorkspaceRoots(Set.of(workspace));
            var infer = new InferConfig(workspace);
            var compiler =
                    new JavaCompilerService(
                            new LinkedHashSet<Path>(infer.classPath()),
                            infer.buildDocPath(),
                            java.util.Collections.emptySet(),
                            java.util.Collections.emptySet());

            var initialCompile = compiler.compileFastWithProcessors(FileStore.all().toArray(Path[]::new));
            try {
                var context =
                        new NavigationContext(
                                compiler,
                                new TypeIndexRouter(
                                        WorkspaceTypeIndex.from(initialCompile),
                                        new ExternalBinaryTypeIndex(compiler)));

                assertLocation(
                        context,
                        use,
                        "model.getName()",
                        "model.".length(),
                        model,
                        8,
                        9,
                        8,
                        16);
                assertLocation(
                        context,
                        use,
                        "model.setName",
                        "model.".length(),
                        model,
                        11,
                        7,
                        11,
                        14);
                assertLocation(
                        context,
                        use,
                        "model.label();",
                        "model.".length(),
                        model,
                        14,
                        9,
                        14,
                        14);
                assertLocation(
                        context,
                        use,
                        "model.label(\"Dr.\")",
                        "model.".length(),
                        model,
                        17,
                        9,
                        17,
                        14);
                assertLocation(
                        context,
                        use,
                        "MethodModel.create(\"Ada\")",
                        "MethodModel.".length(),
                        model,
                        23,
                        21,
                        23,
                        27);
                assertLocation(
                        context,
                        use,
                        "MethodModel.create();",
                        "MethodModel.".length(),
                        model,
                        20,
                        21,
                        20,
                        27);
                assertLocation(
                        context,
                        use,
                        "model.inheritedName()",
                        "model.".length(),
                        model,
                        2,
                        9,
                        2,
                        22);
                assertLocation(
                        context,
                        model,
                        "return getName();",
                        "return ".length(),
                        model,
                        8,
                        9,
                        8,
                        16);
                assertLocation(
                        context,
                        model,
                        "model.setName(name);",
                        "model.".length(),
                        model,
                        11,
                        7,
                        11,
                        14);
            } finally {
                initialCompile.close();
            }
        } finally {
            FileStore.reset();
            deleteRecursively(workspace);
        }
    }

    @Test
    public void compilerLookupResolvesLambdaAndStreamCasesAfterFullCompile() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-stream-compiler");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);

            var model = pkg.resolve("StreamModels.java");
            Files.writeString(
                    model,
                    "package com.example.demo;\n"
                            + "class Foo {\n"
                            + "  private Bar bar;\n"
                            + "  Bar getBar() {\n"
                            + "    return bar;\n"
                            + "  }\n"
                            + "}\n"
                            + "class Bar {\n"
                            + "  String getName() {\n"
                            + "    return \"bar\";\n"
                            + "  }\n"
                            + "  String getCode() {\n"
                            + "    return \"code\";\n"
                            + "  }\n"
                            + "}\n");
            var use = pkg.resolve("StreamUse.java");
            Files.writeString(
                    use,
                    "package com.example.demo;\n"
                            + "import java.util.List;\n"
                            + "import java.util.stream.Collectors;\n"
                            + "class StreamUse {\n"
                            + "  void test(List<Foo> foos) {\n"
                            + "    foos.stream()\n"
                            + "        .map(i -> i.getBar().getName())\n"
                            + "        .collect(Collectors.toList());\n"
                            + "    foos.stream()\n"
                            + "        .map(Foo::getBar)\n"
                            + "        .map(Bar::getName)\n"
                            + "        .collect(Collectors.toList());\n"
                            + "    foos.stream()\n"
                            + "        .map(Foo::getBar)\n"
                            + "        .map(bar -> bar.getCode())\n"
                            + "        .collect(Collectors.toList());\n"
                            + "  }\n"
                            + "}\n");

            FileStore.reset();
            FileStore.setWorkspaceRoots(Set.of(workspace));
            var infer = new InferConfig(workspace);
            var compiler =
                    new JavaCompilerService(
                            new LinkedHashSet<Path>(infer.classPath()),
                            infer.buildDocPath(),
                            java.util.Collections.emptySet(),
                            java.util.Collections.emptySet());

            var initialCompile = compiler.compileFastWithProcessors(FileStore.all().toArray(Path[]::new));
            try {
                var context =
                        new NavigationContext(
                                compiler,
                                new TypeIndexRouter(
                                        WorkspaceTypeIndex.from(initialCompile),
                                        new ExternalBinaryTypeIndex(compiler)));

                assertLocation(
                        context,
                        use,
                        "i.getBar().getName()",
                        "i.".length(),
                        model,
                        3,
                        6,
                        3,
                        12);
                assertLocation(
                        context,
                        use,
                        "i.getBar().getName()",
                        "i.getBar().".length(),
                        model,
                        8,
                        9,
                        8,
                        16);
                assertLocation(
                        context,
                        use,
                        "Foo::getBar",
                        0,
                        model,
                        1,
                        6,
                        1,
                        9);
                assertLocation(
                        context,
                        use,
                        "Foo::getBar",
                        "Foo::".length(),
                        model,
                        3,
                        6,
                        3,
                        12);
                assertLocation(
                        context,
                        use,
                        "Bar::getName",
                        0,
                        model,
                        7,
                        6,
                        7,
                        9);
                assertLocation(
                        context,
                        use,
                        "Bar::getName",
                        "Bar::".length(),
                        model,
                        8,
                        9,
                        8,
                        16);
                assertLocation(
                        context,
                        use,
                        "bar.getCode()",
                        "bar.".length(),
                        model,
                        11,
                        9,
                        11,
                        16);
            } finally {
                initialCompile.close();
            }
        } finally {
            FileStore.reset();
            deleteRecursively(workspace);
        }
    }

    @Test
    public void compilerLookupResolvesInterfaceCasesAfterFullCompile() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-interface-compiler");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);

            var api = pkg.resolve("InterfaceApi.java");
            Files.writeString(
                    api,
                    "package com.example.demo;\n"
                            + "interface ParentApi {\n"
                            + "  default String inheritedDefault() {\n"
                            + "    return \"parent\";\n"
                            + "  }\n"
                            + "}\n"
                            + "@FunctionalInterface\n"
                            + "interface FormatterApi {\n"
                            + "  String format(String input);\n"
                            + "}\n"
                            + "interface OrderApi extends ParentApi {\n"
                            + "  String PREFIX = \"order\";\n"
                            + "  String describe(String id);\n"
                            + "  default String defaultLabel() {\n"
                            + "    return PREFIX;\n"
                            + "  }\n"
                            + "  static String staticLabel() {\n"
                            + "    return PREFIX;\n"
                            + "  }\n"
                            + "}\n");
            var impl = pkg.resolve("DefaultOrderApi.java");
            Files.writeString(
                    impl,
                    "package com.example.demo;\n"
                            + "class DefaultOrderApi implements OrderApi {\n"
                            + "  public String describe(String id) {\n"
                            + "    return id;\n"
                            + "  }\n"
                            + "  String localOnly() {\n"
                            + "    return \"local\";\n"
                            + "  }\n"
                            + "}\n");
            var use = pkg.resolve("InterfaceUse.java");
            Files.writeString(
                    use,
                    "package com.example.demo;\n"
                            + "class InterfaceUse {\n"
                            + "  void test(OrderApi api, DefaultOrderApi concrete) {\n"
                            + "    api.describe(\"42\");\n"
                            + "    api.defaultLabel();\n"
                            + "    api.inheritedDefault();\n"
                            + "    OrderApi.staticLabel();\n"
                            + "    String prefix = OrderApi.PREFIX;\n"
                            + "    concrete.describe(\"42\");\n"
                            + "    concrete.localOnly();\n"
                            + "    FormatterApi formatter = value -> value.trim();\n"
                            + "    formatter.format(\" x \");\n"
                            + "  }\n"
                            + "}\n");

            FileStore.reset();
            FileStore.setWorkspaceRoots(Set.of(workspace));
            var infer = new InferConfig(workspace);
            var compiler =
                    new JavaCompilerService(
                            new LinkedHashSet<Path>(infer.classPath()),
                            infer.buildDocPath(),
                            java.util.Collections.emptySet(),
                            java.util.Collections.emptySet());

            var initialCompile = compiler.compileFastWithProcessors(FileStore.all().toArray(Path[]::new));
            try {
                var context =
                        new NavigationContext(
                                compiler,
                                new TypeIndexRouter(
                                        WorkspaceTypeIndex.from(initialCompile),
                                        new ExternalBinaryTypeIndex(compiler)));

                assertLocation(context, use, "OrderApi api", 0, api, 10, 10, 10, 18);
                assertLocation(context, use, "DefaultOrderApi concrete", 0, impl, 1, 6, 1, 21);
                assertLocation(
                        context,
                        use,
                        "api.describe",
                        "api.".length(),
                        api,
                        12,
                        9,
                        12,
                        17);
                assertLocation(
                        context,
                        use,
                        "api.defaultLabel",
                        "api.".length(),
                        api,
                        13,
                        17,
                        13,
                        29);
                assertLocation(
                        context,
                        use,
                        "api.inheritedDefault",
                        "api.".length(),
                        api,
                        2,
                        17,
                        2,
                        33);
                assertLocation(
                        context,
                        use,
                        "OrderApi.staticLabel",
                        "OrderApi.".length(),
                        api,
                        16,
                        16,
                        16,
                        27);
                assertLocation(
                        context,
                        use,
                        "OrderApi.PREFIX",
                        "OrderApi.".length(),
                        api,
                        11,
                        9,
                        11,
                        15);
                assertLocation(
                        context,
                        use,
                        "concrete.describe",
                        "concrete.".length(),
                        impl,
                        2,
                        16,
                        2,
                        24);
                assertLocation(
                        context,
                        use,
                        "concrete.localOnly",
                        "concrete.".length(),
                        impl,
                        5,
                        9,
                        5,
                        18);
                assertLocation(context, use, "FormatterApi formatter", 0, api, 7, 10, 7, 22);
                assertLocation(
                        context,
                        use,
                        "formatter.format",
                        "formatter.".length(),
                        api,
                        8,
                        9,
                        8,
                        15);
            } finally {
                initialCompile.close();
            }
        } finally {
            FileStore.reset();
            deleteRecursively(workspace);
        }
    }

    @Test
    public void compilerLookupResolvesRecordCasesAfterFullCompile() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-record-compiler");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);

            var recordFile = pkg.resolve("PersonRecord.java");
            Files.writeString(
                    recordFile,
                    "package com.example.demo;\n"
                            + "record PersonRecord(String componentName, int age) {\n"
                            + "  PersonRecord {\n"
                            + "    componentName = componentName.trim();\n"
                            + "  }\n"
                            + "  PersonRecord(String componentName) {\n"
                            + "    this(componentName, 0);\n"
                            + "  }\n"
                            + "  String label() {\n"
                            + "    return componentName;\n"
                            + "  }\n"
                            + "}\n");
            var use = pkg.resolve("RecordUse.java");
            Files.writeString(
                    use,
                    "package com.example.demo;\n"
                            + "class RecordUse {\n"
                            + "  void test() {\n"
                            + "    PersonRecord person = new PersonRecord(\"Ada\", 36);\n"
                            + "    var other = new PersonRecord(\"Grace\");\n"
                            + "    person.componentName();\n"
                            + "    person.label();\n"
                            + "  }\n"
                            + "}\n");

            try (var flow = compilerFlowContext(workspace)) {
                var context = flow.navigation();
                assertLocationAtNeedle(context, use, "PersonRecord person", 0, recordFile, "PersonRecord");
                assertLocationAtNeedle(
                        context,
                        use,
                        "new PersonRecord(\"Ada\", 36)",
                        "new ".length(),
                        recordFile,
                        "PersonRecord {",
                        0,
                        "PersonRecord");
                assertLocationAtNeedle(
                        context,
                        use,
                        "new PersonRecord(\"Grace\")",
                        "new ".length(),
                        recordFile,
                        "PersonRecord(String componentName)",
                        0,
                        "PersonRecord");
                assertLocationAtNeedle(
                        context,
                        use,
                        "person.componentName()",
                        "person.".length(),
                        recordFile,
                        "componentName, int age",
                        0,
                        "componentName");
                assertLocationAtNeedle(context, use, "person.label()", "person.".length(), recordFile, "label");
                assertLocationAtNeedle(
                        context,
                        recordFile,
                        "return componentName;",
                        "return ".length(),
                        recordFile,
                        "componentName, int age",
                        0,
                        "componentName");
            }
        } finally {
            FileStore.reset();
            deleteRecursively(workspace);
        }
    }

    @Test
    public void compilerLookupResolvesAnnotationAndImportCasesAfterFullCompile() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-annotation-import-compiler");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);

            var route = pkg.resolve("Route.java");
            Files.writeString(
                    route,
                    "package com.example.demo;\n"
                            + "@interface Route {\n"
                            + "  String value();\n"
                            + "  String method() default \"GET\";\n"
                            + "}\n");
            var tools = pkg.resolve("ImportTargets.java");
            Files.writeString(
                    tools,
                    "package com.example.demo;\n"
                            + "class ImportedType {}\n"
                            + "class StaticTools {\n"
                            + "  static final String VALUE = \"value\";\n"
                            + "  static String helper() {\n"
                            + "    return VALUE;\n"
                            + "  }\n"
                            + "}\n");
            var use = pkg.resolve("AnnotationImportUse.java");
            Files.writeString(
                    use,
                    "package com.example.demo;\n"
                            + "import com.example.demo.ImportedType;\n"
                            + "import static com.example.demo.StaticTools.VALUE;\n"
                            + "import static com.example.demo.StaticTools.helper;\n"
                            + "@Route(value = \"/orders\", method = \"POST\")\n"
                            + "class AnnotationImportUse {\n"
                            + "  ImportedType imported;\n"
                            + "  void test() {\n"
                            + "    helper();\n"
                            + "    String value = VALUE;\n"
                            + "  }\n"
                            + "}\n");

            try (var flow = compilerFlowContext(workspace)) {
                var context = flow.navigation();
                assertLocationAtNeedle(context, use, "@Route", 1, route, "Route");
                assertLocationAtNeedle(context, use, "value = \"/orders\"", 0, route, "value");
                assertLocationAtNeedle(context, use, "method = \"POST\"", 0, route, "method");
                assertLocationAtNeedle(context, use, "ImportedType imported", 0, tools, "ImportedType");
                assertLocationAtNeedle(context, use, "helper();", 0, tools, "helper");
                assertLocationAtNeedle(context, use, "String value = VALUE;", "String value = ".length(), tools, "VALUE");
            }
        } finally {
            FileStore.reset();
            deleteRecursively(workspace);
        }
    }

    @Test
    public void compilerLookupResolvesThisSuperLocalAndAnonymousCasesAfterFullCompile() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-this-super-compiler");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);

            var file = pkg.resolve("ThisSuperUse.java");
            Files.writeString(
                    file,
                    "package com.example.demo;\n"
                            + "interface Task {\n"
                            + "  void run();\n"
                            + "  default String helper() {\n"
                            + "    return \"help\";\n"
                            + "  }\n"
                            + "}\n"
                            + "class ParentThing {\n"
                            + "  String parentField;\n"
                            + "  String parentMethod() {\n"
                            + "    return parentField;\n"
                            + "  }\n"
                            + "}\n"
                            + "class ChildThing extends ParentThing {\n"
                            + "  String childField;\n"
                            + "  String childMethod() {\n"
                            + "    return childField;\n"
                            + "  }\n"
                            + "  void test() {\n"
                            + "    this.childField = \"child\";\n"
                            + "    this.childMethod();\n"
                            + "    super.parentField = \"parent\";\n"
                            + "    super.parentMethod();\n"
                            + "    Task task = new Task() {\n"
                            + "      public void run() {\n"
                            + "        helper();\n"
                            + "      }\n"
                            + "    };\n"
                            + "    class LocalThing {\n"
                            + "      String localName() {\n"
                            + "        return \"local\";\n"
                            + "      }\n"
                            + "    }\n"
                            + "    var local = new LocalThing();\n"
                            + "    local.localName();\n"
                            + "  }\n"
                            + "}\n");

            try (var flow = compilerFlowContext(workspace)) {
                var context = flow.navigation();
                assertLocationAtNeedle(context, file, "this.childField", "this.".length(), file, "childField");
                assertLocationAtNeedle(context, file, "this.childMethod", "this.".length(), file, "childMethod");
                assertLocationAtNeedle(context, file, "super.parentField", "super.".length(), file, "parentField");
                assertLocationAtNeedle(context, file, "super.parentMethod", "super.".length(), file, "parentMethod");
                assertLocationAtNeedle(context, file, "new Task()", "new ".length(), file, "Task");
                assertLocationAtNeedle(context, file, "helper();", 0, file, "helper");
                assertLocationAtNeedle(context, file, "new LocalThing()", "new ".length(), file, "LocalThing");
                assertLocationAtNeedle(context, file, "local.localName", "local.".length(), file, "localName");
            }
        } finally {
            FileStore.reset();
            deleteRecursively(workspace);
        }
    }

    @Test
    public void compilerLookupResolvesConstructorOverloadCasesAfterFullCompile() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-constructor-overload-compiler");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);

            var file = pkg.resolve("ConstructorOverloadUse.java");
            Files.writeString(
                    file,
                    "package com.example.demo;\n"
                            + "class OverloadedThing {\n"
                            + "  OverloadedThing() {}\n"
                            + "  OverloadedThing(String name) {}\n"
                            + "  OverloadedThing(int size) {}\n"
                            + "}\n"
                            + "class ConstructorOverloadUse {\n"
                            + "  void test() {\n"
                            + "    new OverloadedThing();\n"
                            + "    new OverloadedThing(\"name\");\n"
                            + "    new OverloadedThing(3);\n"
                            + "  }\n"
                            + "}\n");

            try (var flow = compilerFlowContext(workspace)) {
                var context = flow.navigation();
                assertLocationAtNeedle(
                        context, file, "new OverloadedThing();", "new ".length(), file, "OverloadedThing()",
                        0, "OverloadedThing");
                assertLocationAtNeedle(
                        context,
                        file,
                        "new OverloadedThing(\"name\")",
                        "new ".length(),
                        file,
                        "OverloadedThing(String name)",
                        0,
                        "OverloadedThing");
                assertLocationAtNeedle(
                        context,
                        file,
                        "new OverloadedThing(3)",
                        "new ".length(),
                        file,
                        "OverloadedThing(int size)",
                        0,
                        "OverloadedThing");
            }
        } finally {
            FileStore.reset();
            deleteRecursively(workspace);
        }
    }

    @Test
    public void compilerLookupResolvesGenericsAndPackagePrivateCasesAfterFullCompile() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-generics-package-private-compiler");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);

            var hidden = pkg.resolve("HiddenType.java");
            Files.writeString(
                    hidden,
                    "package com.example.demo;\n"
                            + "class HiddenType {\n"
                            + "  String reveal() {\n"
                            + "    return \"hidden\";\n"
                            + "  }\n"
                            + "}\n");
            var box = pkg.resolve("GenericBox.java");
            Files.writeString(
                    box,
                    "package com.example.demo;\n"
                            + "class GenericBox<T extends HiddenType> {\n"
                            + "  private T value;\n"
                            + "  T get() {\n"
                            + "    return value;\n"
                            + "  }\n"
                            + "}\n");
            var use = pkg.resolve("GenericUse.java");
            Files.writeString(
                    use,
                    "package com.example.demo;\n"
                            + "class GenericUse {\n"
                            + "  void test(GenericBox<HiddenType> box, HiddenType direct) {\n"
                            + "    box.get().reveal();\n"
                            + "    direct.reveal();\n"
                            + "  }\n"
                            + "}\n");

            try (var flow = compilerFlowContext(workspace)) {
                var context = flow.navigation();
                assertLocationAtNeedle(context, use, "GenericBox<HiddenType>", 0, box, "GenericBox");
                assertLocationAtNeedle(
                        context,
                        use,
                        "GenericBox<HiddenType>",
                        "GenericBox<".length(),
                        hidden,
                        "HiddenType");
                assertLocationAtNeedle(
                        context,
                        box,
                        "T extends HiddenType",
                        "T extends ".length(),
                        hidden,
                        "HiddenType");
                assertLocationAtNeedle(context, use, "box.get()", "box.".length(), box, "get");
                assertLocationAtNeedle(
                        context,
                        use,
                        "box.get().reveal()",
                        "box.get().".length(),
                        hidden,
                        "reveal");
                assertLocationAtNeedle(
                        context,
                        use,
                        "direct.reveal()",
                        "direct.".length(),
                        hidden,
                        "reveal");
            }
        } finally {
            FileStore.reset();
            deleteRecursively(workspace);
        }
    }

    @Test
    public void compilerLookupAllowsRepeatedDefinitionsAfterFastCompile() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-fast-cache");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);

            var file = pkg.resolve("CacheUse.java");
            Files.writeString(
                    file,
                    "package com.example.demo;\n"
                            + "class CacheUse {\n"
                            + "  void test() {\n"
                            + "    String value = \"cached\";\n"
                            + "    value.isBlank();\n"
                            + "    value.length();\n"
                            + "  }\n"
                            + "}\n");

            try (var flow = compilerFlowContext(workspace)) {
                var context = flow.navigation();

                assertLocationAtNeedle(
                        context,
                        file,
                        "value.isBlank()",
                        0,
                        file,
                        "String value",
                        "String ".length(),
                        "value");
                assertLocationAtNeedle(
                        context,
                        file,
                        "value.length()",
                        0,
                        file,
                        "String value",
                        "String ".length(),
                        "value");
                assertEquals("cache_hit", context.compiler.lastCompileTelemetry().path());
                assertEquals(-1, context.compiler.lastCompileTelemetry().parseMs());
            }
        } finally {
            FileStore.reset();
            deleteRecursively(workspace);
        }
    }

    @Test
    public void resolvesMemberSelectFromAnnotatedLocalReceiver() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-annotated-local-receiver");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);

            var annotation = pkg.resolve("Marker.java");
            Files.writeString(
                    annotation,
                    "package com.example.demo;\n"
                            + "import java.lang.annotation.ElementType;\n"
                            + "import java.lang.annotation.Retention;\n"
                            + "import java.lang.annotation.RetentionPolicy;\n"
                            + "import java.lang.annotation.Target;\n"
                            + "@Target(ElementType.TYPE_USE)\n"
                            + "@Retention(RetentionPolicy.RUNTIME)\n"
                            + "public @interface Marker {}\n");

            var receiver = pkg.resolve("AnnotatedReceiver.java");
            Files.writeString(
                    receiver,
                    "package com.example.demo;\n"
                            + "public class AnnotatedReceiver {\n"
                            + "  public String value() { return \"ok\"; }\n"
                            + "  public int field = 1;\n"
                            + "}\n");

            var service = pkg.resolve("AnnotatedReceiverUsage.java");
            Files.writeString(
                    service,
                    "package com.example.demo;\n"
                            + "class AnnotatedReceiverUsage {\n"
                            + "  void test() {\n"
                            + "    @Marker AnnotatedReceiver receiver = new AnnotatedReceiver();\n"
                            + "    receiver.value();\n"
                            + "    receiver.field++;\n"
                            + "  }\n"
                            + "}\n");

            var context = navigationContext(workspace);
            assertThat(
                    gotoDefinition(context, service, "receiver.value()", "receiver.".length()),
                    hasItem("AnnotatedReceiver.java:3"));
            assertThat(
                    gotoDefinition(context, service, "receiver.field++", "receiver.".length()),
                    hasItem("AnnotatedReceiver.java:4"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void resolvesNestedWorkspaceType() throws Exception {
        assertThat(
                gotoDefinition("/org/javacs/example/GotoEnum.java", "Foos.Foo", "Foos.".length()),
                hasItem("GotoEnum.java:9"));
    }

    @Test
    public void resolvesStaticImportField() throws Exception {
        assertThat(
                gotoDefinition(
                        "/org/javacs/example/GotoStaticImportField.java",
                        "var value = NAME;",
                        "var value = ".length()),
                hasItem("StaticImportInterface.java:4"));
    }

    @Test
    public void resolvesSwitchEnumConstant() throws Exception {
        assertThat(
                gotoDefinition("/org/javacs/example/GotoSwitchCaseEnum.java", "case FOO", "case ".length()),
                hasItem("GotoSwitchCaseEnum.java:5"));
    }

    @Test
    public void resolvesInterfaceConstantInsideStaticMethod() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-interface-constant");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);

            var port = pkg.resolve("AuditPort.java");
            Files.writeString(
                    port,
                    "package com.example.demo;\n"
                            + "import java.util.List;\n"
                            + "public interface AuditPort {\n"
                            + "  List<String> LEVELS = List.of(\"TRACE\", \"DEBUG\");\n"
                            + "  static String hardcodedDecision() {\n"
                            + "    LEVELS.getFirst();\n"
                            + "    return \"x\";\n"
                            + "  }\n"
                            + "}\n");

            assertThat(
                    gotoDefinition(navigationContext(workspace), port, "LEVELS.getFirst()", 0),
                    hasItem("AuditPort.java:4"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void resolvesLombokAccessorOnAbstractSupertypeReceiver() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-abstract-accessor");
        try {
            var modelDir = workspace.resolve("src/com/example/demo/complex/model");
            var serviceDir = workspace.resolve("src/com/example/demo/complex/service");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);

            Files.writeString(
                    modelDir.resolve("ContactWindow.java"),
                    "package com.example.demo.complex.model;\n"
                            + "public class ContactWindow {\n"
                            + "  public int getEndHour() { return 0; }\n"
                            + "}\n");
            Files.writeString(
                    modelDir.resolve("AbstractCustomerRecord.java"),
                    "package com.example.demo.complex.model;\n"
                            + "import lombok.Getter;\n"
                            + "import lombok.Setter;\n"
                            + "@Getter\n"
                            + "@Setter\n"
                            + "public abstract class AbstractCustomerRecord {\n"
                            + "  private ContactWindow contactWindow;\n"
                            + "}\n");
            Files.writeString(
                    modelDir.resolve("CustomerProfile.java"),
                    "package com.example.demo.complex.model;\n"
                            + "import lombok.Getter;\n"
                            + "import lombok.Setter;\n"
                            + "@Getter\n"
                            + "@Setter\n"
                            + "public class CustomerProfile extends AbstractCustomerRecord {\n"
                            + "  private String loyaltyTier;\n"
                            + "}\n");
            Files.writeString(
                    modelDir.resolve("OrderEnvelope.java"),
                    "package com.example.demo.complex.model;\n"
                            + "import lombok.Getter;\n"
                            + "import lombok.Setter;\n"
                            + "@Getter\n"
                            + "@Setter\n"
                            + "public class OrderEnvelope {\n"
                            + "  private CustomerProfile customer;\n"
                            + "}\n");
            var use = serviceDir.resolve("ComplexScenarioService.java");
            Files.writeString(
                    use,
                    "package com.example.demo.complex.service;\n"
                            + "import com.example.demo.complex.model.OrderEnvelope;\n"
                            + "class ComplexScenarioService {\n"
                            + "  void test(OrderEnvelope envelope) {\n"
                            + "    envelope.getCustomer().getContactWindow().getEndHour();\n"
                            + "  }\n"
                            + "}\n");

            assertThat(
                    gotoDefinition(
                            navigationContext(workspace, Set.of(Path.of("lib/lombok-1.18.30.jar").toAbsolutePath())),
                            use,
                            "getContactWindow",
                            0),
                    hasItem("AbstractCustomerRecord.java:7"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void resolvesNestedWorkspaceTypesFromQualifiedUsage() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-nested-types");
        try {
            var modelDir = workspace.resolve("src/com/example/demo/complex/model");
            var serviceDir = workspace.resolve("src/com/example/demo/complex/service");
            var utilDir = workspace.resolve("src/com/example/demo/complex/util");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);
            Files.createDirectories(utilDir);

            Files.writeString(
                    utilDir.resolve("ComplexStatics.java"),
                    "package com.example.demo.complex.util;\n"
                            + "public final class ComplexStatics {\n"
                            + "  private ComplexStatics() {}\n"
                            + "  public static final class NestedFormatter {}\n"
                            + "}\n");
            Files.writeString(
                    modelDir.resolve("DeepGraph.java"),
                    "package com.example.demo.complex.model;\n"
                            + "public class DeepGraph {\n"
                            + "  public static class DeepNode {}\n"
                            + "}\n");
            var service = serviceDir.resolve("ComplexScenarioService.java");
            Files.writeString(
                    service,
                    "package com.example.demo.complex.service;\n"
                            + "import com.example.demo.complex.model.DeepGraph;\n"
                            + "import com.example.demo.complex.util.ComplexStatics;\n"
                            + "class ComplexScenarioService {\n"
                            + "  void test() {\n"
                            + "    var formatter = new ComplexStatics.NestedFormatter();\n"
                            + "    var root = new DeepGraph.DeepNode();\n"
                            + "  }\n"
                            + "  private DeepGraph.DeepNode createLeaf() {\n"
                            + "    return new DeepGraph.DeepNode();\n"
                            + "  }\n"
                            + "}\n");

            assertThat(
                    gotoDefinition(navigationContext(workspace), service, "NestedFormatter()", 0),
                    hasItem("ComplexStatics.java:4"));
            assertThat(
                    gotoDefinition(navigationContext(workspace), service, "DeepNode();", 0),
                    hasItem("DeepGraph.java:3"));
            assertThat(
                    gotoDefinition(navigationContext(workspace), service, "DeepGraph.DeepNode createLeaf", "DeepGraph.".length()),
                    hasItem("DeepGraph.java:3"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void workspaceOwnershipComesFromSnapshotForNestedWorkspaceCandidates() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-owned-types");
        try {
            var modelDir = workspace.resolve("src/com/example/demo/model");
            Files.createDirectories(modelDir);

            Files.writeString(
                    modelDir.resolve("DeepGraph.java"),
                    "package com.example.demo.model;\n"
                            + "public class DeepGraph {\n"
                            + "  public static class DeepNode {}\n"
                            + "}\n");

            var context = navigationContext(workspace);
            assertEquals(
                    true,
                    context.index().isWorkspaceOwnedType(
                            "com.example.demo.model.DeepGraph.DeepNode"));
            assertEquals(
                    true,
                    context.index().isWorkspaceOwnedType(
                            "com.example.demo.model.DeepGraph.DeepNode.Leaf"));
            assertEquals(false, context.index().isWorkspaceOwnedType("java.util.List"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void indexedMemberProvenanceIsExplicitAcrossWorkspaceAndExternalStores() throws Exception {
        var context = navigationContext();

        var workspaceMember =
                context.index()
                        .member("org.javacs.example.GotoSwitchCaseEnum.MyEnum", "FOO", true)
                        .orElseThrow(() -> new AssertionError("Expected workspace enum constant"));
        assertEquals(IndexedMember.Provenance.WORKSPACE, workspaceMember.provenance);

        var externalMember =
                context.index()
                        .external()
                        .member("java.lang.System", "out", true)
                        .orElseThrow(() -> new AssertionError("Expected external JDK field"));
        assertEquals(IndexedMember.Provenance.EXTERNAL_BINARY, externalMember.provenance);
    }

    @Test
    public void resolvesMethodParameterInLocalScope() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-method-parameter");
        try {
            var modelDir = workspace.resolve("src/com/example/demo/model");
            var serviceDir = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);

            Files.writeString(
                    modelDir.resolve("CustomerProfile.java"),
                    "package com.example.demo.model;\n"
                            + "public class CustomerProfile {\n"
                            + "  private String segment;\n"
                            + "  public void setSegment(String segment) { this.segment = segment; }\n"
                            + "}\n");
            var service = serviceDir.resolve("ComplexScenarioService.java");
            Files.writeString(
                    service,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.model.CustomerProfile;\n"
                            + "class ComplexScenarioService {\n"
                            + "  private CustomerProfile sampleEnvelope(String seed) {\n"
                            + "    var customer = new CustomerProfile();\n"
                            + "    customer.setSegment(seed.length() % 2 == 0 ? \"enterprise\" : \"mid-market\");\n"
                            + "    return customer;\n"
                            + "  }\n"
                            + "}\n");

            assertThat(
                    gotoDefinition(navigationContext(workspace), service, "seed.length()", 0),
                    hasItem("ComplexScenarioService.java:4"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void resolvesConstructorIdentifierFromNewClassExpression() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-constructor-identifier");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);
            var file = pkg.resolve("ConstructorIdentifier.java");
            Files.writeString(
                    file,
                    "package com.example.demo;\n"
                            + "class ConstructorIdentifier {\n"
                            + "  ConstructorIdentifier() {}\n"
                            + "  static ConstructorIdentifier create() {\n"
                            + "    return new ConstructorIdentifier();\n"
                            + "  }\n"
                            + "}\n");

            assertThat(
                    gotoDefinition(navigationContext(workspace), file, "new ConstructorIdentifier()", "new ".length()),
                    hasItem("ConstructorIdentifier.java:3"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void resolvesSameFileNestedConstructorIdentifierFromNewClassExpression() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-same-file-nested-constructor");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);
            var file = pkg.resolve("NestedConstructorIdentifier.java");
            Files.writeString(
                    file,
                    "package com.example.demo;\n"
                            + "class NestedConstructorIdentifier {\n"
                            + "  static class Nested {\n"
                            + "    Nested() {}\n"
                            + "  }\n"
                            + "  Nested create() {\n"
                            + "    return new Nested();\n"
                            + "  }\n"
                            + "}\n");

            assertThat(
                    gotoDefinition(navigationContext(workspace), file, "new Nested()", "new ".length()),
                    hasItem("NestedConstructorIdentifier.java:4"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void resolvesWorkspaceAnnotationTypeFromAnnotationUse() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-annotation-type");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);
            Files.writeString(
                    pkg.resolve("Marker.java"),
                    "package com.example.demo;\n"
                            + "public @interface Marker {}\n");
            var file = pkg.resolve("UseMarker.java");
            Files.writeString(
                    file,
                    "package com.example.demo;\n"
                            + "@Marker\n"
                            + "class UseMarker {}\n");

            assertThat(
                    gotoDefinition(navigationContext(workspace), file, "@Marker", 1),
                    hasItem("Marker.java:2"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void resolvesWorkspaceStaticMethodFromQualifiedInvocation() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-qualified-static-method");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);
            var file = pkg.resolve("StaticCall.java");
            Files.writeString(
                    file,
                    "package com.example.demo;\n"
                            + "class StaticCall {\n"
                            + "  static String helper(String value) { return value; }\n"
                            + "  String use() {\n"
                            + "    return StaticCall.helper(\"x\");\n"
                            + "  }\n"
                            + "}\n");

            assertThat(
                    gotoDefinition(navigationContext(workspace), file, "StaticCall.helper", "StaticCall.".length()),
                    hasItem("StaticCall.java:3"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void resolvesEnhancedForVariableInsteadOfEarlierLambdaVariable() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-enhanced-for-scope");
        try {
            var modelDir = workspace.resolve("src/com/example/demo/model");
            var serviceDir = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);

            Files.writeString(
                    modelDir.resolve("LineItem.java"),
                    "package com.example.demo.model;\n"
                            + "public class LineItem {\n"
                            + "  public String getFamily() { return \"\"; }\n"
                            + "  public int getQuantity() { return 0; }\n"
                            + "}\n");
            Files.writeString(
                    modelDir.resolve("OrderEnvelope.java"),
                    "package com.example.demo.model;\n"
                            + "import java.util.List;\n"
                            + "public class OrderEnvelope {\n"
                            + "  public List<LineItem> getItems() { return List.of(); }\n"
                            + "}\n");
            Files.writeString(
                    serviceDir.resolve("PricingPort.java"),
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.model.LineItem;\n"
                            + "import com.example.demo.model.OrderEnvelope;\n"
                            + "import java.math.BigDecimal;\n"
                            + "interface PricingPort {\n"
                            + "  BigDecimal price(OrderEnvelope envelope, LineItem item);\n"
                            + "}\n");
            var service = serviceDir.resolve("ComplexScenarioService.java");
            Files.writeString(
                    service,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.model.OrderEnvelope;\n"
                            + "import java.math.BigDecimal;\n"
                            + "import java.util.stream.Collectors;\n"
                            + "class ComplexScenarioService {\n"
                            + "  private final PricingPort pricingPort = null;\n"
                            + "  private void buildGraph(OrderEnvelope envelope) {\n"
                            + "    envelope.getItems().stream().map(item -> item.getFamily()).collect(Collectors.toList());\n"
                            + "    for (var item : envelope.getItems()) {\n"
                            + "      item.getFamily();\n"
                            + "      var perItem = pricingPort.price(envelope, item);\n"
                            + "      if (perItem.compareTo(new BigDecimal(\"600\")) > 0 && item.getQuantity() > 5) {\n"
                            + "        break;\n"
                            + "      }\n"
                            + "    }\n"
                            + "  }\n"
                            + "}\n");

            assertThat(
                    gotoDefinition(
                            navigationContext(workspace),
                            service,
                            "pricingPort.price(envelope, item)",
                            "pricingPort.price(envelope, ".length()),
                    hasItem("ComplexScenarioService.java:9"));
            assertThat(
                    gotoDefinition(navigationContext(workspace), service, "item.getQuantity()", 0),
                    hasItem("ComplexScenarioService.java:9"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void resolvesSameFileNestedTypesAndRecordAccessors() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-nested-records");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);
            var file = pkg.resolve("NestedDefinitionExample.java");
            Files.writeString(
                    file,
                    "package com.example.demo;\n"
                            + "import java.util.List;\n"
                            + "import java.util.Optional;\n"
                            + "class NestedDefinitionExample {\n"
                            + "  record ResolvedSymbol(List<String> locations) {}\n"
                            + "  ResolvedSymbol resolveSymbol() {\n"
                            + "    return new ResolvedSymbol(List.of());\n"
                            + "  }\n"
                            + "  Optional<ResolvedSymbol> wrap(ResolvedSymbol symbol) {\n"
                            + "    return Optional.of(symbol);\n"
                            + "  }\n"
                            + "  List<String> find() {\n"
                            + "    return resolveSymbol().locations();\n"
                            + "  }\n"
                            + "}\n");

            assertThat(
                    gotoDefinition(navigationContext(workspace), file, "ResolvedSymbol resolveSymbol()", 0),
                    hasItem("NestedDefinitionExample.java:5"));
            assertThat(
                    gotoDefinition(
                            navigationContext(workspace),
                            file,
                            "Optional<ResolvedSymbol>",
                            "Optional<".length()),
                    hasItem("NestedDefinitionExample.java:5"));
            var context = navigationContext(workspace);
            var parse = context.compiler.parse(file);
            assertEquals(
                    Optional.of("com.example.demo.NestedDefinitionExample.ResolvedSymbol"),
                    context.index.resolveTypeName("ResolvedSymbol", parse.root()));
            var cursor = cursor(file, ".locations()", 1);
            var resolved =
                    new DefinitionProvider(
                                    context.compiler,
                                    context.index,
                                    file,
                                    cursor.line,
                                    cursor.character)
                            .resolveSymbol();
            assertEquals("com.example.demo.NestedDefinitionExample.ResolvedSymbol", resolved.qualifiedType());
            assertEquals("locations", resolved.memberName());
            assertFalse(resolved.locations().isEmpty());
            assertThat(
                    gotoDefinition(navigationContext(workspace), file, ".locations()", 1),
                    hasItem("NestedDefinitionExample.java:5"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void resolvesDefinitionsInsideStreamLambdasAndMethodReferences() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-stream-targets");
        try {
            var modelDir = workspace.resolve("src/com/example/demo/model");
            var serviceDir = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);

            var lineItem = modelDir.resolve("LineItem.java");
            Files.writeString(
                    lineItem,
                    "package com.example.demo.model;\n"
                            + "import java.util.stream.Stream;\n"
                            + "public class LineItem {\n"
                            + "  public String getFamily() { return \"family\"; }\n"
                            + "  public boolean isFlagged() { return true; }\n"
                            + "  public Stream<String> tagsStream() { return Stream.of(); }\n"
                            + "}\n");
            var envelope = modelDir.resolve("OrderEnvelope.java");
            Files.writeString(
                    envelope,
                    "package com.example.demo.model;\n"
                            + "import java.util.List;\n"
                            + "public class OrderEnvelope {\n"
                            + "  public List<LineItem> getItems() { return List.of(); }\n"
                            + "}\n");
            var service = serviceDir.resolve("ComplexScenarioService.java");
            Files.writeString(
                    service,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.model.LineItem;\n"
                            + "import com.example.demo.model.OrderEnvelope;\n"
                            + "import java.util.stream.Collectors;\n"
                            + "class ComplexScenarioService {\n"
                            + "  void test(OrderEnvelope envelope) {\n"
                            + "    envelope.getItems().stream().map(LineItem::getFamily).collect(Collectors.toList());\n"
                            + "    envelope.getItems().stream().map(item -> item.getFamily()).collect(Collectors.toList());\n"
                            + "    envelope.getItems().stream().flatMap(LineItem::tagsStream).collect(Collectors.toList());\n"
                            + "    envelope.getItems().stream().filter(item -> item.isFlagged()).collect(Collectors.toList());\n"
                            + "  }\n"
                            + "}\n");

            var context = navigationContext(workspace);
            assertThat(
                    gotoDefinition(context, service, "LineItem::getFamily", "LineItem::".length()),
                    hasItem("LineItem.java:4"));
            assertThat(
                    gotoDefinition(context, service, "item -> item.getFamily()", "item -> item.".length()),
                    hasItem("LineItem.java:4"));
            assertThat(
                    gotoDefinition(context, service, "LineItem::tagsStream", "LineItem::".length()),
                    hasItem("LineItem.java:6"));
            assertThat(
                    gotoDefinition(context, service, "item -> item.isFlagged()", "item -> item.".length()),
                    hasItem("LineItem.java:5"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void streamDefinitionFailsClosedForAmbiguousOrUnsupportedTargets() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-stream-fail-closed");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var modelDir = workspace.resolve("src/com/example/demo/model");
            var serviceDir = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);

            Files.writeString(
                    modelDir.resolve("LineItem.java"),
                    "package com.example.demo.model;\n"
                            + "public class LineItem {\n"
                            + "  public String getFamily() { return \"family\"; }\n"
                            + "}\n");
            var formatter = modelDir.resolve("Formatter.java");
            Files.writeString(
                    formatter,
                    "package com.example.demo.model;\n"
                            + "public class Formatter {\n"
                            + "  public String label(com.example.demo.model.LineItem item) { return item.getFamily(); }\n"
                            + "  public String label(String value) { return value; }\n"
                            + "}\n");
            var envelope = modelDir.resolve("OrderEnvelope.java");
            Files.writeString(
                    envelope,
                    "package com.example.demo.model;\n"
                            + "import java.util.List;\n"
                            + "public class OrderEnvelope {\n"
                            + "  public List<LineItem> getItems() { return List.of(); }\n"
                            + "  @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n"
                            + "  public List rawItems() { return List.of(); }\n"
                            + "}\n");
            var service = serviceDir.resolve("ComplexScenarioService.java");
            Files.writeString(
                    service,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.model.Formatter;\n"
                            + "import com.example.demo.model.LineItem;\n"
                            + "import com.example.demo.model.OrderEnvelope;\n"
                            + "import java.util.stream.Collectors;\n"
                            + "class ComplexScenarioService {\n"
                            + "  private final Formatter formatter = new Formatter();\n"
                            + "  void test(OrderEnvelope envelope) {\n"
                            + "    envelope.getItems().stream().map(formatter::label).collect(Collectors.toList());\n"
                            + "    envelope.rawItems().stream().map(item -> item.toString()).collect(Collectors.toList());\n"
                            + "    envelope.getItems().stream().collect(Collectors.mapping(LineItem::getFamily, Collectors.toList()));\n"
                            + "  }\n"
                            + "}\n");

            var context = navigationContext(workspace);
            assertEquals(
                    List.of(),
                    gotoDefinition(context, service, "formatter::label", "formatter::".length()));
            assertEquals(
                    List.of(),
                    gotoDefinition(context, service, "item -> item.toString()", "item -> item.".length()));
            assertEquals(
                    List.of(),
                    gotoDefinition(context, service, "LineItem::getFamily, Collectors", "LineItem::".length()));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void resolvesDeclarationTargetsWithoutPublishedIndexes() throws Exception {
        var workspace = Files.createTempDirectory("jls-definition-no-index-declarations");
        try {
            var pkg = workspace.resolve("src/com/example/demo");
            Files.createDirectories(pkg);
            var file = pkg.resolve("DeclarationOnly.java");
            Files.writeString(
                    file,
                    "package com.example.demo;\n"
                            + "class DeclarationOnly {\n"
                            + "  private String field;\n"
                            + "  String method(String input) {\n"
                            + "    var local = input + field;\n"
                            + "    return local;\n"
                            + "  }\n"
                            + "}\n");

            FileStore.reset();
            FileStore.setWorkspaceRoots(Set.of(workspace));
            var infer = new InferConfig(workspace);
            var compiler =
                    new JavaCompilerService(
                            infer.classPath(),
                            infer.buildDocPath(),
                            java.util.Collections.emptySet(),
                            java.util.Collections.emptySet());

            var methodCursor = cursor(file, "method(String input)", 0);
            assertThat(
                    new DefinitionProvider(
                                    compiler,
                                    TypeIndexRouter.EMPTY,
                                    file,
                                    methodCursor.line,
                                    methodCursor.character)
                            .find(),
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty()));

            var fieldCursor = cursor(file, "field;", 0);
            assertThat(
                    new DefinitionProvider(
                                    compiler,
                                    TypeIndexRouter.EMPTY,
                                    file,
                                    fieldCursor.line,
                                    fieldCursor.character)
                            .find(),
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty()));

            var localUseCursor = cursor(file, "return local;", "return ".length());
            var localLocations =
                    new DefinitionProvider(
                                    compiler,
                                    TypeIndexRouter.EMPTY,
                                    file,
                                    localUseCursor.line,
                                    localUseCursor.character)
                            .find();
            var renderedLocalLocations = new ArrayList<String>();
            for (var location : localLocations) {
                renderedLocalLocations.add(
                        StringSearch.fileName(location.uri) + ":" + (location.range.start.line + 1));
            }
            assertThat(
                    renderedLocalLocations,
                    hasItem("DeclarationOnly.java:5"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    private List<String> gotoDefinition(String file, String needle, int extraOffset) throws Exception {
        var path = FindResource.path(file);
        var cursor = cursor(path, needle, extraOffset);
        return gotoDefinition(NAVIGATION, path, cursor.line, cursor.character);
    }

    private List<String> gotoDefinition(NavigationContext context, Path path, String needle, int extraOffset)
            throws Exception {
        var cursor = cursor(path, needle, extraOffset);
        return gotoDefinition(context, path, cursor.line, cursor.character);
    }

    private List<String> gotoDefinition(NavigationContext context, Path path, int line, int character) {
        var locations =
                new DefinitionProvider(
                                context.compiler, context.index, path, line, character)
                        .find();
        var results = new ArrayList<String>();
        for (Location location : locations) {
            var fileName = StringSearch.fileName(location.uri);
            results.add(fileName + ":" + (location.range.start.line + 1));
        }
        return results;
    }

    private void assertLocation(
            NavigationContext context,
            Path path,
            String needle,
            int extraOffset,
            Path expectedFile,
            int startLine,
            int startCharacter,
            int endLine,
            int endCharacter)
            throws Exception {
        var cursor = cursor(path, needle, extraOffset);
        var locations =
                new DefinitionProvider(
                                context.compiler, context.index, path, cursor.line, cursor.character)
                        .find();
        assertEquals(1, locations.size());
        assertEquals(expectedFile.toUri(), locations.get(0).uri);
        assertEquals(startLine, locations.get(0).range.start.line);
        assertEquals(startCharacter, locations.get(0).range.start.character);
        assertEquals(endLine, locations.get(0).range.end.line);
        assertEquals(endCharacter, locations.get(0).range.end.character);
    }

    private void assertLocationAtNeedle(
            NavigationContext context,
            Path path,
            String needle,
            int extraOffset,
            Path expectedFile,
            String expectedNeedle)
            throws Exception {
        assertLocationAtNeedle(context, path, needle, extraOffset, expectedFile, expectedNeedle, 0, expectedNeedle);
    }

    private void assertLocationAtNeedle(
            NavigationContext context,
            Path path,
            String needle,
            int extraOffset,
            Path expectedFile,
            String expectedContainerNeedle,
            int expectedSymbolOffset,
            String expectedSymbol)
            throws Exception {
        var cursor = cursor(path, needle, extraOffset);
        var locations =
                new DefinitionProvider(
                                context.compiler, context.index, path, cursor.line, cursor.character)
                        .find();
        assertEquals(1, locations.size());
        assertEquals(expectedFile.toUri(), locations.get(0).uri);

        var expectedSource = Files.readString(expectedFile);
        var expectedOffset = expectedSource.indexOf(expectedContainerNeedle);
        if (expectedOffset < 0) {
            throw new AssertionError("Missing expected needle: " + expectedContainerNeedle);
        }
        expectedOffset += expectedSymbolOffset;
        var startLine = 0;
        var startCharacter = 0;
        for (var i = 0; i < expectedOffset; i++) {
            if (expectedSource.charAt(i) == '\n') {
                startLine++;
                startCharacter = 0;
            } else {
                startCharacter++;
            }
        }
        assertEquals(startLine, locations.get(0).range.start.line);
        assertEquals(startCharacter, locations.get(0).range.start.character);
        assertEquals(startLine, locations.get(0).range.end.line);
        assertEquals(startCharacter + expectedSymbol.length(), locations.get(0).range.end.character);
    }

    private Cursor cursor(Path file, String needle, int extraOffset) throws IOException {
        var source = Files.readString(file);
        var index = source.indexOf(needle);
        if (index < 0) {
            throw new AssertionError("Missing test needle: " + needle);
        }
        var offset = index + extraOffset;
        var line = 1;
        var column = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new Cursor(line, column);
    }

    private static NavigationContext navigationContext() {
        return navigationContext(LanguageServerFixture.DEFAULT_WORKSPACE_ROOT);
    }

    private static NavigationContext navigationContext(Path workspaceRoot) {
        return navigationContext(workspaceRoot, Set.of());
    }

    private static NavigationContext navigationContext(Path workspaceRoot, Set<Path> extraClassPath) {
        FileStore.reset();
        FileStore.setWorkspaceRoots(Set.of(workspaceRoot));
        var infer = new InferConfig(workspaceRoot);
        var classPath = new LinkedHashSet<Path>(infer.classPath());
        classPath.addAll(extraClassPath);
        var compiler =
                new JavaCompilerService(
                        classPath,
                        infer.buildDocPath(),
                        java.util.Collections.emptySet(),
                        java.util.Collections.emptySet());
        TypeIndexRouter index;
        try (var task = compiler.compileFastWithProcessors(FileStore.all().toArray(Path[]::new))) {
            index =
                    new TypeIndexRouter(
                            WorkspaceTypeIndex.from(task),
                            new ExternalBinaryTypeIndex(compiler));
        }
        return new NavigationContext(compiler, index);
    }

    private static CompilerFlowContext compilerFlowContext(Path workspaceRoot) {
        FileStore.reset();
        FileStore.setWorkspaceRoots(Set.of(workspaceRoot));
        var infer = new InferConfig(workspaceRoot);
        var compiler =
                new JavaCompilerService(
                        new LinkedHashSet<Path>(infer.classPath()),
                        infer.buildDocPath(),
                        java.util.Collections.emptySet(),
                        java.util.Collections.emptySet());
        var compile = compiler.compileFastWithProcessors(FileStore.all().toArray(Path[]::new));
        var index =
                new TypeIndexRouter(
                        WorkspaceTypeIndex.from(compile),
                        new ExternalBinaryTypeIndex(compiler));
        return new CompilerFlowContext(new NavigationContext(compiler, index), compile);
    }

    private record Cursor(int line, int character) {}

    private record NavigationContext(JavaCompilerService compiler, TypeIndexRouter index) {}

    private record CompilerFlowContext(NavigationContext navigation, CompileTask compile)
            implements AutoCloseable {
        @Override
        public void close() {
            compile.close();
            FileStore.reset();
        }
    }

    private static class TestLogCapture extends Handler {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record != null && record.getMessage() != null) {
                lines.add(record.getMessage());
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        int countContaining(String needle) {
            var count = 0;
            for (var line : lines) {
                if (line.contains(needle)) {
                    count++;
                }
            }
            return count;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(
                    path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}
