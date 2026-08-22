package org.javacs;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import org.javacs.lsp.*;
import org.javacs.rewrite.*;

/**
 * Handles the rename flow: prepareRename, rename, renameApplied, and all supporting
 * helper methods (createRewrite, renameMethod, renameField, renameVariable, canRename, canFindSource).
 */
final class RenameHandler {
    private static final Logger LOG = Logger.getLogger("main");

    private final JavaLanguageServer server;

    RenameHandler(JavaLanguageServer server) {
        this.server = server;
    }

    Optional<RenameResponse> prepareRename(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        LOG.info("Try to rename...");
        var file = Paths.get(params.textDocument.uri);
        var requestCompiler = server.compilerFor(file);
        try (var task = requestCompiler.compile(file)) {
            long cursor;
            try {
                cursor =
                        FileStore.offset(
                                task.root(file).getSourceFile().getCharContent(true).toString(),
                                params.position.line + 1,
                                params.position.character + 1);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            var path = new FindNameAt(task).scan(task.root(file), cursor);
            if (path == null) {
                LOG.info("...no element under cursor");
                return Optional.empty();
            }
            var el = task.trees.getElement(path);
            if (el == null) {
                LOG.info("...couldn't resolve element");
                return Optional.empty();
            }
            if (!canRename(el)) {
                LOG.info(String.format("...can't rename %s", el));
                return Optional.empty();
            }
            if (!canFindSource(requestCompiler, el)) {
                LOG.info(String.format("...can't find source for %s", el));
                return Optional.empty();
            }
            var response = new RenameResponse();
            response.range = FindHelper.location(task, path, "").range;
            response.placeholder = el.getSimpleName().toString();
            return Optional.of(response);
        }
    }

    WorkspaceEdit rename(RenameParams params, LanguageClient client) {
        server.moduleRegistry.includeMavenReferenceSources();
        var file = Paths.get(params.textDocument.uri);
        var requestCompiler = server.compilerFor(file);
        var rw = createRewrite(params, requestCompiler);
        var response = new WorkspaceEdit();
        var map = rw instanceof RenameField field
                ? field.rewrite(requestCompiler, server::compilerFor, server::canReferenceModule)
                : rw instanceof RenameMethod method
                        ? method.rewrite(requestCompiler, server::compilerFor, server::canReferenceModule)
                        : rw.rewrite(requestCompiler);
        for (var editedFile : map.keySet()) {
            response.changes.put(editedFile.toUri(), Arrays.asList(map.get(editedFile)));
        }
        // For class renames, notify client to rename the file on disk
        if (rw instanceof RenameClass rc) {
            var sourceFile = requestCompiler.findTypeDeclaration(rc.oldQualifiedName);
            if (sourceFile != null && sourceFile != CompilerProvider.NOT_FOUND) {
                var oldPath = sourceFile.toAbsolutePath().normalize().toString();
                var parent = sourceFile.getParent();
                var newFileName = rc.newSimpleName + ".java";
                var newPath =
                        parent != null
                                ? parent.resolve(newFileName).toAbsolutePath().normalize().toString()
                                : newFileName;
                var notificationParams = new HashMap<String, String>();
                notificationParams.put("oldPath", oldPath);
                notificationParams.put("newPath", newPath);
                client.customNotification("java/renameFile", new Gson().toJsonTree(notificationParams));
            }
        }
        return response;
    }

    void renameApplied(DidChangeWatchedFilesParams params) {
        if (params == null || params.changes == null) return;
        for (var change : params.changes) {
            var file = Paths.get(change.uri);
            if (!FileStore.isWorkspaceJavaFile(change.uri) || !Files.exists(file)) continue;
            var requestCompiler = server.compilerFor(file);
            var parse = requestCompiler.parse(file);
            if (LombokAnnotations.hasStructuralLombokAnnotation(parse.root())) {
                requestCompiler.refreshBuildOutput(file);
            }
        }
    }

    private boolean canRename(Element rename) {
        return switch (rename.getKind()) {
            case METHOD, FIELD, LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER, CLASS -> true;
            default -> false;
        };
    }

    private boolean canFindSource(CompilerProvider requestCompiler, Element rename) {
        if (rename == null) return false;
        if (rename instanceof TypeElement type) {
            var name = type.getQualifiedName().toString();
            return requestCompiler.findTypeDeclaration(name) != CompilerProvider.NOT_FOUND;
        }
        return canFindSource(requestCompiler, rename.getEnclosingElement());
    }

    private Rewrite createRewrite(RenameParams params, CompilerProvider requestCompiler) {
        var file = Paths.get(params.textDocument.uri);
        try (var task = requestCompiler.compile(file)) {
            long position;
            try {
                position =
                        FileStore.offset(
                                task.root(file).getSourceFile().getCharContent(true).toString(),
                                params.position.line + 1,
                                params.position.character + 1);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            var path = new FindNameAt(task).scan(task.root(file), position);
            if (path == null) return Rewrite.NOT_SUPPORTED;
            var el = task.trees.getElement(path);
            return switch (el.getKind()) {
                case METHOD -> renameMethod(task, (ExecutableElement) el, params.newName);
                case FIELD -> renameField(task, (VariableElement) el, params.newName);
                case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER ->
                        renameVariable(task, (VariableElement) el, params.newName);
                case CLASS -> {
                    var type = (TypeElement) el;
                    yield new RenameClass(type.getQualifiedName().toString(), params.newName);
                }
                default -> Rewrite.NOT_SUPPORTED;
            };
        }
    }

    private RenameMethod renameMethod(CompileTask task, ExecutableElement method, String newName) {
        var parent = (TypeElement) method.getEnclosingElement();
        var className = parent.getQualifiedName().toString();
        var methodName = method.getSimpleName().toString();
        var erasedParameterTypes = new String[method.getParameters().size()];
        for (var i = 0; i < erasedParameterTypes.length; i++) {
            var type = method.getParameters().get(i).asType();
            erasedParameterTypes[i] = task.types.erasure(type).toString();
        }
        return new RenameMethod(className, methodName, erasedParameterTypes, newName);
    }

    private RenameField renameField(CompileTask task, VariableElement field, String newName) {
        var parent = (TypeElement) field.getEnclosingElement();
        var className = parent.getQualifiedName().toString();
        var fieldName = field.getSimpleName().toString();
        return new RenameField(className, fieldName, newName);
    }

    private RenameVariable renameVariable(CompileTask task, VariableElement variable, String newName) {
        var trees = task.trees;
        var path = trees.getPath(variable);
        var file = Paths.get(path.getCompilationUnit().getSourceFile().toUri());
        var position = trees.getSourcePositions().getStartPosition(path.getCompilationUnit(), path.getLeaf());
        return new RenameVariable(file, (int) position, newName);
    }
}
