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
    /**
     * Return lexical scopes that participate in Java member lookup.
     *
     * <p>The Scope API appends compilation-unit/import scopes after the last real class scope. Those
     * outer scopes are useful for import completion, but they do not model lexical enclosing classes
     * and methods. Trimming by a fixed count was brittle across JDK scope layouts and could drop a
     * real enclosing class scope for nested types, which in turn hid inherited members and valid
     * {@code TypeName.this}/{@code TypeName.super} completions.
     */
    static List<Scope> fastScopes(Scope start) {
        var scopes = new ArrayList<Scope>();
        for (var s = start; s != null; s = s.getEnclosingScope()) {
            if (s.getEnclosingClass() == null && s.getEnclosingMethod() == null) {
                break;
            }
            scopes.add(s);
        }
        return scopes;
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
