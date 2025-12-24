package org.javacs.completion;

import com.sun.source.tree.Scope;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.DeclaredType;
import org.javacs.CompileTask;

class ScopeHelper {
    // TODO is this still necessary? Test speed. We could get rid of the extra static-imports step.
    static List<Scope> fastScopes(Scope start) {
        var scopes = new ArrayList<Scope>();
        for (var s = start; s != null; s = s.getEnclosingScope()) {
            scopes.add(s);
        }
        // Scopes may be contained in an enclosing scope.
        // The outermost scope contains those elements available via "star import" declarations;
        // the scope within that contains the top level elements of the compilation unit, including any named
        // imports.
        // https://parent.docs.oracle.com/en/java/javase/11/docs/api/jdk.compiler/com/sun/source/tree/Scope.html
        return scopes.subList(0, scopes.size() - 2);
    }

    static List<Element> scopeMembers(CompileTask task, Scope inner, Predicate<CharSequence> filter) {
        var trees = Trees.instance(task.task);
        var elements = task.task.getElements();
        var isStatic = false;
        var list = new ArrayList<Element>();
        for (var scope : fastScopes(inner)) {
            if (scope.getEnclosingMethod() != null) {
                isStatic = isStatic || scope.getEnclosingMethod().getModifiers().contains(Modifier.STATIC);
            }
            for (var member : scope.getLocalElements()) {
                if (!filter.test(member.getSimpleName())) continue;
                if (isStatic && member.getSimpleName().contentEquals("this")) continue;
                if (isStatic && member.getSimpleName().contentEquals("super")) continue;
                list.add(member);
            }
            if (scope.getEnclosingClass() != null) {
                var typeElement = scope.getEnclosingClass();
                var typeType = (DeclaredType) typeElement.asType();
                for (var member : elements.getAllMembers(typeElement)) {
                    if (!filter.test(member.getSimpleName())) continue;
                    if (!trees.isAccessible(scope, member, typeType)) continue;
                    if (isStatic && !member.getModifiers().contains(Modifier.STATIC)) continue;
                    list.add(member);
                }
                isStatic = isStatic || typeElement.getModifiers().contains(Modifier.STATIC);
            }
        }
        return list;
    }
}
