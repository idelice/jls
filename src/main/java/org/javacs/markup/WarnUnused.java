package org.javacs.markup;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.util.*;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;

class WarnUnused extends TreeScanner<Void, Void> {
    // Copied from TreePathScanner
    // We need to be able to call scan(path, _) recursively
    private TreePath path;

    private void scanPath(TreePath path) {
        TreePath prev = this.path;
        this.path = path;
        try {
            path.getLeaf().accept(this, null);
        } finally {
            this.path = prev; // So we can call scan(path, _) recursively
        }
    }

    @Override
    public Void scan(Tree tree, Void p) {
        if (tree == null) return null;

        TreePath prev = path;
        path = new TreePath(path, tree);
        try {
            return tree.accept(this, p);
        } finally {
            path = prev;
        }
    }

    private final Trees trees;
    private final Map<Element, TreePath> privateDeclarations = new HashMap<>(), localVariables = new HashMap<>();
    private final Map<Element, TreePath> nonPrivateDeclarations = new HashMap<>();
    private final Set<Element> used = new HashSet<>();

    WarnUnused(JavacTask task) {
        this.trees = Trees.instance(task);
    }

    Set<Element> notUsed() {
        var unused = new HashSet<Element>();
        unused.addAll(privateDeclarations.keySet());
        unused.addAll(localVariables.keySet());
        unused.removeAll(used);
        // Remove if there are any null elements somehow ended up being added
        // during async work which calls `lint`
        unused.removeIf(Objects::isNull);
        // Remove if <error > field was injected while forming the AST
        unused.removeIf(i -> i.toString().equals("<error>"));
        return unused;
    }

    /** Non-private members not referenced within the same file. Needs workspace confirmation. */
    Set<Element> potentiallyUnusedNonPrivate() {
        var unused = new HashSet<Element>();
        unused.addAll(nonPrivateDeclarations.keySet());
        unused.removeAll(used);
        unused.removeIf(Objects::isNull);
        unused.removeIf(i -> i.toString().equals("<error>"));
        return unused;
    }

    private void foundPrivateDeclaration() {
        privateDeclarations.put(trees.getElement(path), path);
    }

    private void foundNonPrivateDeclaration() {
        nonPrivateDeclarations.put(trees.getElement(path), path);
    }

    private void foundLocalVariable() {
        localVariables.put(trees.getElement(path), path);
    }

    private void foundReference() {
        var toEl = trees.getElement(path);
        if (toEl == null) {
            return;
        }
        if (toEl.asType().getKind() == TypeKind.ERROR) {
            foundPseudoReference(toEl);
            return;
        }
        sweep(toEl);
    }

    private void foundPseudoReference(Element toEl) {
        var parent = toEl.getEnclosingElement();
        if (!(parent instanceof TypeElement)) {
            return;
        }
        var memberName = toEl.getSimpleName();
        var type = (TypeElement) parent;
        for (var member : type.getEnclosedElements()) {
            if (member.getSimpleName().contentEquals(memberName)) {
                sweep(member);
            }
        }
    }

    private void sweep(Element toEl) {
        var firstUse = used.add(toEl);
        var notScanned = firstUse && privateDeclarations.containsKey(toEl);
        if (notScanned) {
            scanPath(privateDeclarations.get(toEl));
        }
    }

    private boolean isReachable(TreePath path) {
        // Check if t is reachable because it's public
        var t = path.getLeaf();
        if (t instanceof VariableTree) {
            var v = (VariableTree) t;

            // Record components are always reachable — they have implicit public accessors
            var parent = path.getParentPath().getLeaf();
            var isStatic = v.getModifiers().getFlags().contains(Modifier.STATIC);
             if (parent.getKind() == Tree.Kind.RECORD && !isStatic) {
                return true;
            }

            var isPrivate = v.getModifiers().getFlags().contains(Modifier.PRIVATE);
            if (!isPrivate || isLocalVariable(path)) {
                return true;
            }
        }
        if (t instanceof MethodTree) {
            var m = (MethodTree) t;
            var isPrivate = m.getModifiers().getFlags().contains(Modifier.PRIVATE);
            var isEmptyConstructor = m.getParameters().isEmpty() && m.getReturnType() == null;
            if (!isPrivate || isEmptyConstructor) {
                return true;
            }
        }
        if (t instanceof ClassTree) {
            var c = (ClassTree) t;
            var isPrivate = c.getModifiers().getFlags().contains(Modifier.PRIVATE);
            if (!isPrivate) {
                return true;
            }
        }
        // Check if t has been referenced by a reachable element
        var el = trees.getElement(path);
        return used.contains(el);
    }

    /** Should this non-private member be tracked for potential unused detection? */
    private boolean shouldTrackNonPrivate(TreePath path) {
        var t = path.getLeaf();
        if (t instanceof MethodTree m) {
            var isEmptyConstructor = m.getParameters().isEmpty() && m.getReturnType() == null;
            if (isEmptyConstructor) return false;
            var name = m.getName().toString();
            if (name.equals("<init>") || name.equals("main")) return false;
            if (name.equals("equals") || name.equals("hashCode") || name.equals("toString")) return false;
            for (var ann : m.getModifiers().getAnnotations()) {
                if (ann.getAnnotationType().toString().contains("Override")) return false;
            }
            return true;
        }
        if (t instanceof VariableTree v) {
            if (v.getName().toString().equals("serialVersionUID")) return false;
            return true;
        }
        if (t instanceof ClassTree) return true;
        return false;
    }

    private boolean isRecordConstructorParam(TreePath path) {
        if (path.getLeaf().getKind() != Tree.Kind.VARIABLE) return false;
        var parent = path.getParentPath();
        if (parent == null || !(parent.getLeaf() instanceof MethodTree method)) return false;
        if (method.getReturnType() != null) return false; // not a constructor
        var grandParent = parent.getParentPath();
        if (grandParent == null) return false;
        return grandParent.getLeaf().getKind() == Tree.Kind.RECORD;
    }

    /** Record component implicit fields: parent is the RECORD ClassTree itself. */
    private boolean isRecordComponentField(TreePath path) {
        if (path.getLeaf().getKind() != Tree.Kind.VARIABLE) return false;
        var parent = path.getParentPath();
        if (parent == null) return false;
        var parentLeaf = parent.getLeaf();
        if (!(parentLeaf instanceof ClassTree)) return false;
        if (parentLeaf.getKind() != Tree.Kind.RECORD) return false;
        var v = (VariableTree) path.getLeaf();
        return !v.getModifiers().getFlags().contains(Modifier.STATIC);
    }

    private boolean isLocalVariable(TreePath path) {
        var kind = path.getLeaf().getKind();
        if (kind != Tree.Kind.VARIABLE) {
            return false;
        }
        var parent = path.getParentPath().getLeaf().getKind();
        if (parent == Tree.Kind.CLASS
                || parent == Tree.Kind.INTERFACE
                || parent == Tree.Kind.ENUM
                || parent == Tree.Kind.RECORD
                || parent == Tree.Kind.ANNOTATION_TYPE) {
            return false;
        }
        if (parent == Tree.Kind.METHOD) {
            var method = (MethodTree) path.getParentPath().getLeaf();
            if (method.getBody() == null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Void visitVariable(VariableTree t, Void __) {
        if (isRecordConstructorParam(path) || isRecordComponentField(path)) {
            super.visitVariable(t, null);
        } else if (isLocalVariable(path)) {
            foundLocalVariable();
            super.visitVariable(t, null);
        } else if (isReachable(path)) {
            if (shouldTrackNonPrivate(path)) foundNonPrivateDeclaration();
            super.visitVariable(t, null);
        } else {
            foundPrivateDeclaration();
        }
        return null;
    }

    @Override
    public Void visitMethod(MethodTree t, Void __) {
        if (isReachable(path)) {
            if (shouldTrackNonPrivate(path)) foundNonPrivateDeclaration();
            super.visitMethod(t, null);
        } else {
            foundPrivateDeclaration();
        }
        return null;
    }

    @Override
    public Void visitClass(ClassTree t, Void __) {
        if (isReachable(path)) {
            if (shouldTrackNonPrivate(path)) foundNonPrivateDeclaration();
            super.visitClass(t, null);
        } else {
            foundPrivateDeclaration();
        }
        return null;
    }

    @Override
    public Void visitIdentifier(IdentifierTree t, Void __) {
        foundReference();
        return super.visitIdentifier(t, null);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree t, Void __) {
        foundReference();
        return super.visitMemberSelect(t, null);
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree t, Void __) {
        foundReference();
        return super.visitMemberReference(t, null);
    }

    @Override
    public Void visitNewClass(NewClassTree t, Void __) {
        foundReference();
        return super.visitNewClass(t, null);
    }
}
