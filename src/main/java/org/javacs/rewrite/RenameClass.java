package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import javax.lang.model.element.TypeElement;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class RenameClass implements Rewrite {
    public final String oldQualifiedName;
    public final String newSimpleName;

    public RenameClass(String oldQualifiedName, String newSimpleName) {
        this.oldQualifiedName = oldQualifiedName;
        this.newSimpleName = newSimpleName;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        var oldSimpleName = oldQualifiedName.substring(oldQualifiedName.lastIndexOf('.') + 1);
        if (oldSimpleName.equals(newSimpleName)) return CANCELLED;

        var sourceFile = compiler.findTypeDeclaration(oldQualifiedName);
        if (sourceFile == null || sourceFile.equals(CompilerProvider.NOT_FOUND)) {
            return CANCELLED;
        }

        var referenceFiles = compiler.findTypeReferences(oldQualifiedName);
        var allPaths = new LinkedHashSet<Path>();
        allPaths.add(sourceFile);
        Collections.addAll(allPaths, referenceFiles);

        try (var compile = compiler.compile(allPaths.toArray(new Path[0]))) {
            var trees = Trees.instance(compile.task);
            var oldType = compile.task.getElements().getTypeElement(oldQualifiedName);
            if (oldType == null) {
                return CANCELLED;
            }

            var result = new HashMap<Path, TextEdit[]>();

            for (var root : compile.roots) {
                var file = Paths.get(root.getSourceFile().toUri());
                List<TreePath> references = new ArrayList<>();

                // For the source file: only rename class declaration and constructors.
                // FindTypeReferences is NOT used here — Lombok annotations on the class
                // produce trees whose Trees.getElement() returns the class TypeElement,
                // creating false positives that corrupt the file.
                if (file.equals(sourceFile)) {
                    var declPath = classDeclarationPath(root, oldType, trees);
                    if (declPath != null) references.add(declPath);
                    var ctorPaths = constructorNamePaths(root, oldType, oldSimpleName, trees);
                    references.addAll(ctorPaths);
                } else {
                    // For reference files: find all type usages of the old class name
                    references = findTypeReferences(root, oldType, compile.task);
                }

                // Deduplicate references by position — identical-range edits corrupt
                // when applied sequentially by the client.
                var seen = new LinkedHashSet<TreePath>();
                var skip = new HashSet<Long>();
                for (var ref : references) {
                    if (ref.getLeaf() instanceof SyntheticTree) {
                        var pos = ((SyntheticTree) ref.getLeaf()).position();
                        if (!skip.add(pos)) continue;
                    }
                    seen.add(ref);
                }

                if (!seen.isEmpty()) {
                    var fileEdits = replaceAll(new ArrayList<>(seen), newSimpleName, compile.task);
                    result.put(file, fileEdits);
                    LOG.info("renameEdits file=" + file.getFileName() + " refs=" + references.size() + " edits=" + fileEdits.length);
                }
            }

            // If only deleted edits (file remove), don't lose them
            if (!result.isEmpty()) return result;
            return CANCELLED;
        }
    }

    private List<TreePath> findTypeReferences(
            CompilationUnitTree root, TypeElement oldType, JavacTask task) {
        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        var oldSimpleName = oldQualifiedName.substring(oldQualifiedName.lastIndexOf('.') + 1);
        var found = new ArrayList<TreePath>();

        // Read the source content once so we can verify positions for IdentifierTree nodes.
        // Lombok annotation processing injects synthetic IdentifierTree nodes whose
        // id.getName() correctly returns the class simple name, but whose source position
        // points at a variable/field name instead of the actual type reference site.
        // Checking the raw source chars at [start, start+len) is the only reliable way
        // to reject these synthetic nodes without rejecting legitimate type references.
        CharSequence srcContent = null;
        try {
            srcContent = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            LOG.warning("findTypeReferences: could not read source for " + root.getSourceFile().getName() + ": " + e.getMessage());
        }
        final var src = srcContent;

        Consumer<TreePath> forEach =
                path -> {
                    var leaf = path.getLeaf();
                    if (leaf instanceof IdentifierTree id) {
                        if (!id.getName().contentEquals(oldSimpleName)) return;
                        // Source-position guard: Lombok synthetic nodes have the correct
                        // name but their recorded position points at the variable/field name.
                        // Verify the actual source text at [start, start+oldSimpleName.length())
                        // is exactly oldSimpleName — if not, discard this synthetic node.
                        if (src != null) {
                            var start = pos.getStartPosition(root, leaf);
                            if (start < 0) return;
                            var end = start + oldSimpleName.length();
                            if (end > src.length()) return;
                            var actual = src.subSequence((int) start, (int) end).toString();
                            if (!actual.equals(oldSimpleName)) {
                                return;
                            }
                        }
                    } else if (leaf instanceof MemberSelectTree ms) {
                        if (!ms.getIdentifier().contentEquals(oldSimpleName)) return;
                    } else {
                        return;
                    }
                    var element = trees.getElement(path);
                    if (oldType.equals(element)) {
                        found.add(path);
                    }
                };
        new FindTypeReferences().scan(root, forEach);
        return found;
    }

    /**
     * TreeScanner that visits IdentifierTree and MemberSelectTree only.
     * VariablesTree and MemberReferenceTree are intentionally excluded — they
     * represent declarations or method references, not type name usage sites.
     */
    private static class FindTypeReferences extends TreePathScanner<Void, Consumer<TreePath>> {
        @Override
        public Void visitIdentifier(IdentifierTree node, Consumer<TreePath> forEach) {
            forEach.accept(getCurrentPath());
            return super.visitIdentifier(node, forEach);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Consumer<TreePath> forEach) {
            forEach.accept(getCurrentPath());
            return super.visitMemberSelect(node, forEach);
        }
    }

    /** Get a synthetic TreePath for the class name in the class declaration. */
    private TreePath classDeclarationPath(
            CompilationUnitTree root, TypeElement oldType, Trees trees) {
        var classTree = trees.getTree(oldType);
        if (classTree == null) return null;
        var pos = trees.getSourcePositions();
        var classStart = pos.getStartPosition(root, classTree);
        if (classStart < 0) return null;
        try {
            var content = root.getSourceFile().getCharContent(true);
            var snippet = content.subSequence(
                    (int) classStart,
                    Math.min((int) classStart + 1000, content.length())).toString();
            var m = java.util.regex.Pattern.compile(
                    "(class|interface|enum|@interface)\\s+" +
                    java.util.regex.Pattern.quote(classTree.getSimpleName().toString())).matcher(snippet);
            if (m.find()) {
                var nameStart = (int) classStart + m.start(1) + m.group(1).length();
                while (nameStart < content.length()
                        && Character.isWhitespace(content.charAt(nameStart))) {
                    nameStart++;
                }
                return new TreePath(
                        trees.getPath(root, classTree), new SyntheticTree(nameStart));
            }
        } catch (IOException e) {
            // fall through
        }
        return null;
    }

    /** Get TreePaths for constructor method names. */
    private List<TreePath> constructorNamePaths(
            CompilationUnitTree root, TypeElement oldType, String oldSimpleName, Trees trees) {
        var classTree = trees.getTree(oldType);
        if (classTree == null) return List.of();
        var pos = trees.getSourcePositions();
        var paths = new ArrayList<TreePath>();
        try {
            var content = root.getSourceFile().getCharContent(true);
            for (var member : classTree.getMembers()) {
                if (!(member instanceof MethodTree)) continue;
                var method = (MethodTree) member;
                if (method.getReturnType() != null) continue; // not a constructor
                var methodStart = pos.getStartPosition(root, method);
                if (methodStart < 0) continue;
                var snippet = content.subSequence(
                        (int) methodStart,
                        Math.min((int) methodStart + 500, content.length())).toString();
                var m = java.util.regex.Pattern.compile(
                        java.util.regex.Pattern.quote(oldSimpleName)).matcher(snippet);
                if (m.find()) {
                    var nameStart = (int) methodStart + m.start();
                    paths.add(new TreePath(
                            trees.getPath(root, method), new SyntheticTree(nameStart)));
                }
            }
        } catch (IOException e) {
            // fall through
        }
        return paths;
    }

    /** Minimal tree node carrying an absolute byte-offset position. */
    private static class SyntheticTree implements Tree {
        private final long pos;

        SyntheticTree(long pos) { this.pos = pos; }

        @Override public Kind getKind() { return Kind.OTHER; }
        @Override public <R, D> R accept(TreeVisitor<R, D> v, D d) { return null; }

        long position() { return pos; }
    }

    /**
     * Create TextEdits from TreePaths using exact SourcePosition ranges for
     * IdentifierTree/MemberSelectTree, and byte-offsets from SyntheticTree for
     * class-declaration and constructor names.
     */
    private TextEdit[] replaceAll(List<TreePath> paths, String newName, JavacTask task) {
        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        var edits = new ArrayList<TextEdit>();

        for (var p : paths) {
            var root = p.getCompilationUnit();
            var lines = root.getLineMap();
            var leaf = p.getLeaf();

            long nameStart, nameEnd;

            if (leaf instanceof SyntheticTree) {
                nameStart = ((SyntheticTree) leaf).position();
                nameEnd = nameStart + oldQualifiedName.substring(
                        oldQualifiedName.lastIndexOf('.') + 1).length();
            } else if (leaf instanceof IdentifierTree) {
                var id = (IdentifierTree) leaf;
                nameStart = pos.getStartPosition(root, id);
                nameEnd = nameStart + id.getName().length();
            } else if (leaf instanceof MemberSelectTree) {
                var select = (MemberSelectTree) leaf;
                var expr = select.getExpression();
                if (expr == null) continue;
                var exprEnd = pos.getEndPosition(root, expr);
                if (exprEnd < 0) continue;
                nameStart = findName(root, exprEnd, select.getIdentifier(), task);
                nameEnd = nameStart + select.getIdentifier().length();
            } else {
                continue;
            }

            if (nameStart < 0 || nameEnd <= nameStart) continue;

            var startLine = (int) lines.getLineNumber(nameStart);
            var startCol = (int) lines.getColumnNumber(nameStart);
            var endLine = (int) lines.getLineNumber(nameEnd);
            var endCol = (int) lines.getColumnNumber(nameEnd);

            edits.add(new TextEdit(
                    new Range(
                            new Position(startLine - 1, startCol - 1),
                            new Position(endLine - 1, endCol - 1)),
                    newName));
        }

        return edits.toArray(new TextEdit[0]);
    }

    private long findName(CompilationUnitTree root, long startPos, CharSequence name, JavacTask task) {
        if (startPos < 0) return -1;
        try {
            var contents = root.getSourceFile().getCharContent(true);
            var matcher = java.util.regex.Pattern.compile(
                    "\\b" + name + "\\b").matcher(contents);
            if (matcher.find((int) startPos)) return matcher.start();
            return startPos;
        } catch (IOException e) {
            return -1;
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
