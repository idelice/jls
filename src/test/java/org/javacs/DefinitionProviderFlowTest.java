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
import org.javacs.completion.TypeIndexRouter;
import org.javacs.completion.ExternalBinaryTypeIndex;
import org.javacs.completion.WorkspaceTypeIndex;
import org.javacs.index.IndexedMember;
import org.javacs.navigation.DefinitionProvider;
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
        try (var task = compiler.compile(FileStore.all().toArray(Path[]::new))) {
            index =
                    new TypeIndexRouter(
                            WorkspaceTypeIndex.from(task),
                            new ExternalBinaryTypeIndex(compiler));
        }
        return new NavigationContext(compiler, index);
    }

    private record Cursor(int line, int character) {}

    private record NavigationContext(JavaCompilerService compiler, TypeIndexRouter index) {}

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
