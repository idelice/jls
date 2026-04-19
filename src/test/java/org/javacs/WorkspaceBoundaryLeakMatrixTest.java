package org.javacs;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.javacs.lsp.DidChangeConfigurationParams;
import org.javacs.lsp.DidChangeTextDocumentParams;
import org.javacs.lsp.DidCloseTextDocumentParams;
import org.javacs.lsp.DidOpenTextDocumentParams;
import org.javacs.lsp.DidSaveTextDocumentParams;
import org.javacs.lsp.LanguageClient;
import org.javacs.lsp.Position;
import org.javacs.lsp.PublishDiagnosticsParams;
import org.javacs.lsp.Range;
import org.javacs.lsp.ReferenceParams;
import org.javacs.lsp.ShowMessageParams;
import org.javacs.lsp.TextDocumentContentChangeEvent;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs.lsp.TextDocumentPositionParams;
import org.junit.Assert;
import org.junit.Test;

public class WorkspaceBoundaryLeakMatrixTest {
    private static final List<String> WORKSPACE_TYPES =
            List.of(
                    "matrix.model.PlainPojo",
                    "matrix.model.PlainPojo.Nested",
                    "matrix.model.PlainPojo.NestedInt",
                    "matrix.model.PlainPojo.NestedEnum",
                    "matrix.model.PlainPojo.NestedRecord",
                    "matrix.model.MyAnno",
                    "matrix.model.MyRecord",
                    "matrix.model.MyEnum",
                    "matrix.model.LombokPojo",
                    "matrix.model.LombokBase",
                    "matrix.model.LombokChild",
                    "matrix.model.LombokLogger",
                    "matrix.model.MyInt",
                    "matrix.model.MyInt2",
                    "matrix.model.MySealed",
                    "matrix.use.ServiceTwo",
                    "matrix.use.DefinitionMatrixUse",
                    "matrix.use.ReferenceMatrixUse",
                    "matrix.use.CompletionMatrixUse",
                    "matrix.use.LoggerCompletionUse");

    @Test
    public void workspaceDefinitionMatrixDoesNotLeakToExternalBinary() throws Exception {
        var workspace = createWorkspace();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var server = LanguageServerFixture.getJavaLanguageServer(workspace.root(), new NoopLanguageClient());
            configureLombokClasspath(server);

            open(server, workspace.serviceTwo());
            open(server, workspace.definitionUse());
            open(server, workspace.myEnum());
            open(server, workspace.plainPojo());
            open(server, workspace.myRecord());
            open(server, workspace.lombokPojo());
            open(server, workspace.lombokBase());
            open(server, workspace.myInt());
            open(server, workspace.myInt2());
            open(server, workspace.myAnno());

            Assert.assertTrue(awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_ANNOTATION*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_CONSTRUCTOR*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_PLAIN_FIELD*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_INSTANCE_METHOD*/");
            assertDefinitionCase(server, capture, workspace.serviceTwo(), "/*USE_UNQUALIFIED_CALL*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_STATIC_FIELD*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_STATIC_METHOD*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_NESTED_CLASS*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_NESTED_METHOD*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_NESTED_ENUM_CONSTANT*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_ENUM_CONSTANT*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_LOMBOK_ENUM_ACCESSOR*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_LOMBOK_ACCESSOR*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_LOMBOK_INHERITED_ACCESSOR*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_DEFAULT_METHOD*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_ABSTRACT_METHOD*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_SEALED_METHOD*/");
            assertDefinitionCase(server, capture, workspace.definitionUse(), "/*USE_NESTED_INTERFACE_METHOD*/");
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace.root());
        }
    }

    @Test
    public void workspaceReferenceMatrixDoesNotLeakToExternalBinary() throws Exception {
        var workspace = createWorkspace();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var server = LanguageServerFixture.getJavaLanguageServer(workspace.root(), new NoopLanguageClient());
            configureLombokClasspath(server);

            open(server, workspace.referenceUse());
            open(server, workspace.myEnum());
            open(server, workspace.myInt());
            open(server, workspace.myInt2());
            open(server, workspace.plainPojo());
            open(server, workspace.lombokPojo());

            Assert.assertTrue(awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            assertReferencesCase(server, capture, workspace.myEnum(), "/*DECL_ENUM_CONSTANT*/");
            assertReferencesCase(server, capture, workspace.myInt(), "/*DECL_INTERFACE_METHOD*/");
            assertReferencesCase(server, capture, workspace.myInt(), "/*DECL_INTERFACE_DEFAULT_METHOD*/");
            assertReferencesCase(server, capture, workspace.myInt2(), "/*DECL_ABSTRACT_METHOD*/");
            assertReferencesCase(server, capture, workspace.plainPojo(), "/*DECL_NESTED_INTERFACE_METHOD*/");
            assertReferencesCase(server, capture, workspace.lombokPojo(), "/*DECL_LOMBOK_NAME_FIELD*/");
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace.root());
        }
    }

    @Test
    public void workspaceBoundaryMatrixSurvivesDirtySaveAndReopen() throws Exception {
        var workspace = createWorkspace();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var server = LanguageServerFixture.getJavaLanguageServer(workspace.root(), new NoopLanguageClient());
            configureLombokClasspath(server);

            open(server, workspace.completionUse());
            open(server, workspace.myEnum());
            Assert.assertTrue(awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            assertCompletionCase(server, capture, workspace.completionUse(), "MyEnum.FIRST/*COMP_ENUM*/.", "getType");

            var changedText = Files.readString(workspace.completionUse()).replace("return MyEnum.FIRST", "return MyEnum.FIRST");
            var change = new DidChangeTextDocumentParams();
            change.textDocument.uri = workspace.completionUse().toUri();
            change.textDocument.version = 2;
            var delta = new TextDocumentContentChangeEvent();
            delta.text = changedText + "\n// dirty-boundary-check";
            change.contentChanges.add(delta);
            server.didChangeTextDocument(change);

            assertCompletionCase(server, capture, workspace.completionUse(), "MyEnum.FIRST/*COMP_ENUM*/.", "getType");

            var save = new DidSaveTextDocumentParams();
            save.textDocument = new TextDocumentIdentifier(workspace.completionUse().toUri());
            server.didSaveTextDocument(save);
            assertCompletionCase(server, capture, workspace.completionUse(), "MyEnum.FIRST/*COMP_ENUM*/.", "getType");

            var close = new DidCloseTextDocumentParams();
            close.textDocument = new TextDocumentIdentifier(workspace.completionUse().toUri());
            server.didCloseTextDocument(close);
            open(server, workspace.completionUse());
            assertCompletionCase(server, capture, workspace.completionUse(), "MyEnum.FIRST/*COMP_ENUM*/.", "getType");
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace.root());
        }
    }

    @Test
    public void jdkCompletionStillWorksAfterWorkspaceBoundaryCleanup() throws Exception {
        var workspace = createWorkspace();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var server = LanguageServerFixture.getJavaLanguageServer(workspace.root(), new NoopLanguageClient());
            open(server, workspace.jdkCompletionUse());
            Assert.assertTrue(awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            capture.clear();
            var completion =
                    server.completion(
                                    new TextDocumentPositionParams(
                                            new TextDocumentIdentifier(workspace.jdkCompletionUse().toUri()),
                                            positionAfter(workspace.jdkCompletionUse(), "\"\"/*COMP_JDK*/.")))
                            .orElseThrow();
            Assert.assertTrue(
                    "expected JDK completion members after workspace-boundary cleanup",
                    completion.items.stream().anyMatch(item -> "length".equals(item.label)));
            Assert.assertEquals(
                    "JDK completion should not be reported as a workspace leak",
                    0,
                    capture.countContaining("[workspace-boundary] external_leak"));
            assertCompletionPerfIsCompilerFree(capture, workspace.jdkCompletionUse());
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace.root());
        }
    }

    private static void assertDefinitionCase(
            JavaLanguageServer server, TestLogCapture capture, Path file, String marker) throws Exception {
        capture.clear();
        var result =
                server.gotoDefinition(
                        new TextDocumentPositionParams(
                                new TextDocumentIdentifier(file.toUri()), positionAtMarker(file, marker)));
        Assert.assertTrue("expected definition result at " + marker, result.isPresent());
        Assert.assertFalse("expected non-empty definition result at " + marker, result.get().isEmpty());
        assertNoWorkspaceLeak(capture);
    }

    private static void assertReferencesCase(
            JavaLanguageServer server, TestLogCapture capture, Path file, String marker) throws Exception {
        capture.clear();
        var params = new ReferenceParams();
        params.textDocument = new TextDocumentIdentifier(file.toUri());
        params.position = positionAtMarker(file, marker);
        params.context = new org.javacs.lsp.ReferenceContext();
        params.context.includeDeclaration = true;
        var result = server.findReferences(params);
        Assert.assertTrue("expected references result at " + marker, result.isPresent());
        Assert.assertFalse("expected non-empty references result at " + marker, result.get().isEmpty());
        assertNoWorkspaceLeak(capture);
    }

    private static void assertCompletionCase(
            JavaLanguageServer server, TestLogCapture capture, Path file, String anchor, String expectedLabel)
            throws Exception {
        capture.clear();
        var completion =
                server.completion(
                                new TextDocumentPositionParams(
                                        new TextDocumentIdentifier(file.toUri()), positionAfter(file, anchor)))
                        .orElseThrow();
        if (expectedLabel != null) {
            Assert.assertTrue(
                    "expected completion item '" + expectedLabel + "' at " + anchor,
                    completion.items.stream().anyMatch(item -> expectedLabel.equals(item.label)));
        }
        assertNoWorkspaceLeak(capture);
        assertCompletionPerfIsCompilerFree(capture, file);
    }

    private static void assertNoWorkspaceLeak(TestLogCapture capture) {
        Assert.assertEquals(
                "workspace candidates must not hit the boundary guard: " + capture.linesMatching(".*\\[workspace-boundary\\].*"),
                0,
                capture.countContaining("[workspace-boundary] external_leak"));
        for (var workspaceType : WORKSPACE_TYPES) {
            Assert.assertEquals(
                    "workspace type must not hit external-binary: "
                            + workspaceType
                            + " "
                            + capture.linesMatching(".*\\[external-binary\\].*type=.*"),
                    0,
                    capture.countMatching(".*\\[external-binary\\].*type=" + java.util.regex.Pattern.quote(workspaceType) + ".*"));
        }
    }

    private static void assertCompletionPerfIsCompilerFree(TestLogCapture capture, Path file) {
        var line = capture.lastLineContaining("[perf] completion_flow file=" + file.getFileName());
        Assert.assertNotNull("expected completion_flow log for " + file.getFileName(), line);
        Assert.assertTrue("completion should report zero enter phases: " + line, line.contains("enter=0"));
        Assert.assertTrue("completion should report zero analyze phases: " + line, line.contains("analyze=0"));
        Assert.assertTrue("completion should report zero annotation-processing phases: " + line, line.contains("ap=0"));
    }

    private static Position positionAtMarker(Path file, String marker) throws Exception {
        var text = Files.readString(file);
        var index = text.indexOf(marker);
        Assert.assertTrue("missing marker " + marker + " in " + file, index >= 0);
        return positionAt(text, index + marker.length());
    }

    private static Position positionAfter(Path file, String anchor) throws Exception {
        var text = Files.readString(file);
        var index = text.indexOf(anchor);
        Assert.assertTrue("missing anchor " + anchor + " in " + file, index >= 0);
        return positionAt(text, index + anchor.length());
    }

    private static Position endOfFile(Path file) throws Exception {
        return positionAt(Files.readString(file), Files.readString(file).length());
    }

    private static Position positionAt(String text, int offset) {
        var line = 0;
        var column = 0;
        for (var i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 0;
            } else {
                column++;
            }
        }
        return new Position(line, column);
    }

    private static void open(JavaLanguageServer server, Path file) throws Exception {
        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = Files.readString(file);
        server.didOpenTextDocument(open);
    }

    private static long completionIndexVersion(JavaLanguageServer server) throws Exception {
        var field = JavaLanguageServer.class.getDeclaredField("completionIndexVersion");
        field.setAccessible(true);
        return ((AtomicLong) field.get(server)).get();
    }

    private static boolean awaitCompletionIndexAdvance(
            JavaLanguageServer server, long before, long timeout, TimeUnit unit) throws Exception {
        var deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (completionIndexVersion(server) > before) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static void configureLombokClasspath(JavaLanguageServer server) {
        var settings = new JsonObject();
        var java = new JsonObject();
        var classPath = new com.google.gson.JsonArray();
        classPath.add(Paths.get("lib/lombok-1.18.30.jar").toAbsolutePath().toString());
        java.add("classPath", classPath);
        settings.add("java", java);
        var change = new DidChangeConfigurationParams();
        change.settings = settings;
        server.didChangeConfiguration(change);
    }

    private static MatrixWorkspace createWorkspace() throws Exception {
        var root = Files.createTempDirectory("workspace-boundary-matrix");
        var models = root.resolve("src/matrix/model");
        var use = root.resolve("src/matrix/use");
        Files.createDirectories(models);
        Files.createDirectories(use);

        var plainPojo = models.resolve("PlainPojo.java");
        Files.writeString(
                plainPojo,
                "package matrix.model;\n"
                        + "public class PlainPojo {\n"
                        + "  public int /*DECL_PLAIN_FIELD*/count;\n"
                        + "  public static final String /*DECL_STATIC_FIELD*/TYPE = \"plain\";\n"
                        + "  public /*DECL_CONSTRUCTOR*/PlainPojo() {}\n"
                        + "  public int /*DECL_INSTANCE_METHOD*/countUp(int delta) { return count + delta; }\n"
                        + "  public static String /*DECL_STATIC_METHOD*/makeType() { return TYPE; }\n"
                        + "  public static class /*DECL_NESTED_CLASS*/Nested {\n"
                        + "    public String /*DECL_NESTED_METHOD*/nestedValue() { return TYPE; }\n"
                        + "  }\n"
                        + "  public interface /*DECL_NESTED_INTERFACE*/NestedInt {\n"
                        + "    String /*DECL_NESTED_INTERFACE_METHOD*/nestedRun();\n"
                        + "    default String /*DECL_DEFAULT_METHOD*/nestedDefault(String value) { return value; }\n"
                        + "  }\n"
                        + "  public enum /*DECL_NESTED_ENUM*/NestedEnum { /*DECL_NESTED_ENUM_CONSTANT*/ONE; }\n"
                        + "  public record /*DECL_NESTED_RECORD*/NestedRecord(String /*DECL_NESTED_RECORD_COMPONENT*/value) {}\n"
                        + "}\n");

        var myAnno = models.resolve("MyAnno.java");
        Files.writeString(
                myAnno,
                "package matrix.model;\n"
                        + "public @interface /*DECL_ANNOTATION*/MyAnno {}\n");

        var myRecord = models.resolve("MyRecord.java");
        Files.writeString(
                myRecord,
                "package matrix.model;\n"
                        + "public record /*DECL_RECORD*/MyRecord(String /*DECL_RECORD_COMPONENT*/value) {}\n");

        var myEnum = models.resolve("MyEnum.java");
        Files.writeString(
                myEnum,
                "package matrix.model;\n"
                        + "import lombok.AllArgsConstructor;\n"
                        + "import lombok.Getter;\n"
                        + "@Getter\n"
                        + "@AllArgsConstructor\n"
                        + "public enum /*DECL_ENUM*/MyEnum {\n"
                        + "  /*DECL_ENUM_CONSTANT*/FIRST(\"asd\");\n"
                        + "  private final String /*DECL_LOMBOK_FIELD*/type;\n"
                        + "}\n");

        var lombokPojo = models.resolve("LombokPojo.java");
        Files.writeString(
                lombokPojo,
                "package matrix.model;\n"
                        + "import lombok.Data;\n"
                        + "@Data\n"
                        + "public class LombokPojo {\n"
                        + "  private String /*DECL_LOMBOK_NAME_FIELD*/name;\n"
                        + "}\n");

        var lombokBase = models.resolve("LombokBase.java");
        Files.writeString(
                lombokBase,
                "package matrix.model;\n"
                        + "import lombok.Getter;\n"
                        + "@Getter\n"
                        + "public class LombokBase {\n"
                        + "  private final String /*DECL_LOMBOK_INHERITED_FIELD*/inherited;\n"
                        + "  public LombokBase(String inherited) { this.inherited = inherited; }\n"
                        + "}\n");

        var lombokChild = models.resolve("LombokChild.java");
        Files.writeString(
                lombokChild,
                "package matrix.model;\n"
                        + "public class LombokChild extends LombokBase {\n"
                        + "  public LombokChild(String inherited) { super(inherited); }\n"
                        + "}\n");

        var lombokLogger = models.resolve("LombokLogger.java");
        Files.writeString(
                lombokLogger,
                "package matrix.model;\n"
                        + "import lombok.extern.slf4j.Slf4j;\n"
                        + "@Slf4j\n"
                        + "public class LombokLogger {\n"
                        + "  public void run() { log.info(\"hi\"); }\n"
                        + "}\n");

        var myInt = models.resolve("MyInt.java");
        Files.writeString(
                myInt,
                "package matrix.model;\n"
                        + "public interface MyInt {\n"
                        + "  String /*DECL_INTERFACE_METHOD*/work(String value);\n"
                        + "  default String /*DECL_INTERFACE_DEFAULT_METHOD*/fallback(String value) { return work(value); }\n"
                        + "}\n");

        Files.writeString(
                models.resolve("MyIntImpl.java"),
                "package matrix.model;\n"
                        + "public class MyIntImpl implements MyInt {\n"
                        + "  @Override public String work(String value) { return value; }\n"
                        + "}\n");

        var myInt2 = models.resolve("MyInt2.java");
        Files.writeString(
                myInt2,
                "package matrix.model;\n"
                        + "public abstract class MyInt2 {\n"
                        + "  public abstract String /*DECL_ABSTRACT_METHOD*/work(String value);\n"
                        + "}\n");

        Files.writeString(
                models.resolve("MyInt2Impl.java"),
                "package matrix.model;\n"
                        + "public class MyInt2Impl extends MyInt2 {\n"
                        + "  @Override public String work(String value) { return value; }\n"
                        + "}\n");

        Files.writeString(
                models.resolve("MySealed.java"),
                "package matrix.model;\n"
                        + "public sealed interface MySealed permits MySealedImpl {\n"
                        + "  String /*DECL_SEALED_METHOD*/sealedName();\n"
                        + "}\n");

        Files.writeString(
                models.resolve("MySealedImpl.java"),
                "package matrix.model;\n"
                        + "public final class MySealedImpl implements MySealed {\n"
                        + "  @Override public String sealedName() { return \"sealed\"; }\n"
                        + "}\n");

        Files.writeString(
                models.resolve("NestedIntImpl.java"),
                "package matrix.model;\n"
                        + "public class NestedIntImpl implements PlainPojo.NestedInt {\n"
                        + "  @Override public String nestedRun() { return \"nested\"; }\n"
                        + "}\n");

        var serviceTwo = use.resolve("ServiceTwo.java");
        Files.writeString(
                serviceTwo,
                "package matrix.use;\n"
                        + "class ServiceTwo {\n"
                        + "  String doit() { return \"ok\"; }\n"
                        + "  String run() { return /*USE_UNQUALIFIED_CALL*/doit(); }\n"
                        + "}\n");

        var definitionUse = use.resolve("DefinitionMatrixUse.java");
        Files.writeString(
                definitionUse,
                "package matrix.use;\n"
                        + "import static matrix.model.PlainPojo.TYPE;\n"
                        + "import static matrix.model.PlainPojo.makeType;\n"
                        + "import matrix.model.*;\n"
                        + "@/*USE_ANNOTATION*/MyAnno\n"
                        + "class DefinitionMatrixUse {\n"
                        + "  private final PlainPojo field = new PlainPojo();\n"
                        + "  String run(MyInt iface, MyInt2 abs, LombokPojo lombokPojo, LombokChild child, MySealed sealed) {\n"
                        + "    var plain = new /*USE_CONSTRUCTOR*/PlainPojo();\n"
                        + "    var nested = new PlainPojo./*USE_NESTED_CLASS*/Nested();\n"
                        + "    var record = new MyRecord(\"x\");\n"
                        + "    var nestedRecord = new PlainPojo.NestedRecord(\"y\");\n"
                        + "    return field./*USE_PLAIN_FIELD*/count\n"
                        + "        + plain./*USE_INSTANCE_METHOD*/countUp(1)\n"
                        + "        + /*USE_STATIC_FIELD*/TYPE.length()\n"
                        + "        + /*USE_STATIC_METHOD*/makeType().length()\n"
                        + "        + nested./*USE_NESTED_METHOD*/nestedValue().length()\n"
                        + "        + PlainPojo.NestedEnum./*USE_NESTED_ENUM_CONSTANT*/ONE.name().length()\n"
                        + "        + record./*USE_RECORD_ACCESSOR*/value().length()\n"
                        + "        + nestedRecord./*USE_NESTED_RECORD_ACCESSOR*/value().length()\n"
                        + "        + MyEnum./*USE_ENUM_CONSTANT*/FIRST.getType().length()\n"
                        + "        + MyEnum.FIRST./*USE_LOMBOK_ENUM_ACCESSOR*/getType().length()\n"
                        + "        + lombokPojo./*USE_LOMBOK_ACCESSOR*/getName().length()\n"
                        + "        + child./*USE_LOMBOK_INHERITED_ACCESSOR*/getInherited().length()\n"
                        + "        + iface./*USE_DEFAULT_METHOD*/fallback(\"x\").length()\n"
                        + "        + abs./*USE_ABSTRACT_METHOD*/work(\"y\").length()\n"
                        + "        + sealed./*USE_SEALED_METHOD*/sealedName().length()\n"
                        + "        + new NestedIntImpl()./*USE_NESTED_INTERFACE_METHOD*/nestedRun().length();\n"
                        + "  }\n"
                        + "}\n");

        var referenceUse = use.resolve("ReferenceMatrixUse.java");
        Files.writeString(
                referenceUse,
                "package matrix.use;\n"
                        + "import matrix.model.*;\n"
                        + "class ReferenceMatrixUse {\n"
                        + "  String run(MyInt iface, MyInt2 abs, MySealed sealed, PlainPojo.NestedInt nested, LombokPojo lombokPojo) {\n"
                        + "    return iface./*REF_INTERFACE_CALL*/work(\"a\")\n"
                        + "        + new MyIntImpl()./*REF_INTERFACE_IMPL_CALL*/work(\"b\")\n"
                        + "        + iface./*REF_DEFAULT_CALL*/fallback(\"c\")\n"
                        + "        + abs./*REF_ABSTRACT_CALL*/work(\"d\")\n"
                        + "        + new MyInt2Impl()./*REF_ABSTRACT_IMPL_CALL*/work(\"e\")\n"
                        + "        + sealed./*REF_SEALED_CALL*/sealedName()\n"
                        + "        + nested./*REF_NESTED_INTERFACE_CALL*/nestedRun()\n"
                        + "        + nested./*REF_NESTED_DEFAULT_CALL*/nestedDefault(\"n\")\n"
                        + "        + MyEnum./*REF_ENUM_USE*/FIRST.getType()\n"
                        + "        + lombokPojo./*REF_LOMBOK_ACCESSOR_USE*/getName();\n"
                        + "  }\n"
                        + "}\n");

        var completionUse = use.resolve("CompletionMatrixUse.java");
        Files.writeString(
                completionUse,
                "package matrix.use;\n"
                        + "import matrix.model.MyEnum;\n"
                        + "import matrix.model.PlainPojo;\n"
                        + "class CompletionMatrixUse {\n"
                        + "  String doit() { return \"ok\"; }\n"
                        + "  String enumCompletion() { return MyEnum.FIRST/*COMP_ENUM*/.; }\n"
                        + "  String thisCompletion() { return this/*COMP_THIS*/.; }\n"
                        + "  String recordCompletion() { var record = new matrix.model.MyRecord(\"x\"); return record/*COMP_RECORD*/.; }\n"
                        + "  String nestedCompletion() { return new PlainPojo.Nested()/*COMP_NESTED*/.; }\n"
                        + "}\n");

        var loggerCompletionUse = use.resolve("LoggerCompletionUse.java");
        Files.writeString(
                loggerCompletionUse,
                "package matrix.use;\n"
                        + "import lombok.extern.slf4j.Slf4j;\n"
                        + "@Slf4j\n"
                        + "class LoggerCompletionUse {\n"
                        + "  void run() { log/*COMP_LOG*/.; }\n"
                        + "}\n");

        var jdkCompletionUse = use.resolve("JdkCompletionUse.java");
        Files.writeString(
                jdkCompletionUse,
                "package matrix.use;\n"
                        + "class JdkCompletionUse {\n"
                        + "  String run() { return \"\"/*COMP_JDK*/.; }\n"
                        + "}\n");

        return new MatrixWorkspace(
                root,
                plainPojo,
                myAnno,
                myRecord,
                myEnum,
                lombokPojo,
                lombokBase,
                lombokChild,
                lombokLogger,
                myInt,
                myInt2,
                serviceTwo,
                definitionUse,
                referenceUse,
                completionUse,
                loggerCompletionUse,
                jdkCompletionUse);
    }

    private record MatrixWorkspace(
            Path root,
            Path plainPojo,
            Path myAnno,
            Path myRecord,
            Path myEnum,
            Path lombokPojo,
            Path lombokBase,
            Path lombokChild,
            Path lombokLogger,
            Path myInt,
            Path myInt2,
            Path serviceTwo,
            Path definitionUse,
            Path referenceUse,
            Path completionUse,
            Path loggerCompletionUse,
            Path jdkCompletionUse) {}

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ex) {
                                    throw new RuntimeException(ex);
                                }
                            });
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException io) {
                throw io;
            }
            throw ex;
        }
    }

    private static class TestLogCapture extends Handler {
        private final java.util.concurrent.CopyOnWriteArrayList<String> lines =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record == null || record.getLevel().intValue() < Level.FINE.intValue()) {
                return;
            }
            lines.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        int countContaining(String needle) {
            var count = 0;
            for (var line : lines) {
                if (line != null && line.contains(needle)) {
                    count++;
                }
            }
            return count;
        }

        int countMatching(String pattern) {
            var count = 0;
            for (var line : lines) {
                if (line != null && line.matches(pattern)) {
                    count++;
                }
            }
            return count;
        }

        List<String> linesMatching(String pattern) {
            var matches = new java.util.ArrayList<String>();
            for (var line : lines) {
                if (line != null && line.matches(pattern)) {
                    matches.add(line);
                }
            }
            return matches;
        }

        String lastLineContaining(String needle) {
            for (var i = lines.size() - 1; i >= 0; i--) {
                var line = lines.get(i);
                if (line != null && line.contains(needle)) {
                    return line;
                }
            }
            return null;
        }

        void clear() {
            lines.clear();
        }
    }

    private static class NoopLanguageClient implements LanguageClient {
        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {}

        @Override
        public void showMessage(ShowMessageParams params) {}

        @Override
        public void registerCapability(String method, com.google.gson.JsonElement options) {}

        @Override
        public void customNotification(String method, com.google.gson.JsonElement params) {}
    }
}
