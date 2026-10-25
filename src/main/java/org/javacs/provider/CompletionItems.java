package org.javacs.provider;

import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import java.util.List;
import java.util.StringJoiner;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import org.javacs.CompletionData;
import org.javacs.ElementSignatureHelper;
import org.javacs.JsonHelper;
import org.javacs.index.IndexedMember;
import org.javacs.lsp.Command;
import org.javacs.lsp.CompletionItem;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.CompletionItemLabelDetails;
import org.javacs.lsp.InsertTextFormat;
import org.javacs.resolve.TypeNames;

/** Builds completion presentation and insertion text without resolving symbols. */
final class CompletionItems {
    private CompletionItems() {}

    static void setLabelDetails(CompletionItem item, String detail, String description) {
        if (detail == null && description == null) return;
        item.labelDetails = new CompletionItemLabelDetails();
        item.labelDetails.detail = detail;
        item.labelDetails.description = description;
    }

    static CompletionItem packageItem(String name) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = CompletionItemKind.Module;
        i.sortText = sortKey(Priority.PACKAGE_MEMBER, i.label);
        return i;
    }

    static CompletionItem classItem(String className, String compilerId, boolean addParentheses) {
        var i = new CompletionItem();
        i.label = TypeNames.simpleName(className);
        i.kind = CompletionItemKind.Class;
        i.detail = className;
        setLabelDetails(i, null, className);
        i.sortText = sortKey(Priority.IMPORTED_CLASS, i.label);
        var data = new CompletionData();
        data.className = className;
        data.compilerId = compilerId;
        i.data = JsonHelper.GSON.toJsonTree(data);
        if (addParentheses) setInvocation(i, i.label, false);
        return i;
    }

    static CompletionItem snippetItem(String label, String snippet) {
        var i = new CompletionItem();
        i.label = label;
        i.kind = CompletionItemKind.Snippet;
        i.insertText = snippet;
        i.insertTextFormat = InsertTextFormat.Snippet;
        i.sortText = sortKey(Priority.SNIPPET, i.label);
        return i;
    }

    static CompletionItem packageSnippet(String name) {
        return snippetItem("package " + name, "package " + name + ";\n\n");
    }

    static CompletionItem classSnippet(String name) {
        return snippetItem("class " + name, "class " + name + " {\n    $0\n}");
    }

    static CompletionItem interfaceSnippet(String name) {
        return snippetItem("interface " + name, "interface " + name + " {\n    $0\n}");
    }

    static CompletionItem recordSnippet(String name) {
        return snippetItem("record " + name, "record " + name + "($0) {\n}");
    }

    static CompletionItem enumSnippet(String name) {
        return snippetItem("enum " + name, "enum " + name + " {\n    $0\n}");
    }

    static CompletionItem keyword(String keyword) {
        var i = new CompletionItem();
        i.label = keyword;
        i.kind = CompletionItemKind.Keyword;
        i.sortText = sortKey(Priority.KEYWORD, i.label);
        return i;
    }

    static CompletionItem variable(String name, String type) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = CompletionItemKind.Variable;
        i.detail = type;
        setLabelDetails(i, null, type);
        i.sortText = sortKey(Priority.LOCAL, i.label);
        return i;
    }

    static CompletionItem field(String name, String type) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = CompletionItemKind.Field;
        i.detail = type;
        setLabelDetails(i, null, type);
        i.sortText = sortKey(Priority.FIELD, i.label);
        return i;
    }

    static CompletionItem syntacticMethod(String name, MethodTree method, boolean endsWithParen) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = CompletionItemKind.Method;
        var returnType = method.getReturnType() != null ? method.getReturnType().toString() : "void";
        i.detail = returnType;
        setLabelDetails(i, syntaxMethodParameters(method), returnType);
        i.sortText = sortKey(Priority.METHOD, i.label);
        if (!endsWithParen) setInvocation(i, name, method.getParameters().isEmpty());
        return i;
    }

    static CompletionItem syntacticType(String name, Tree.Kind kind, String ownerType, boolean addParentheses) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = kind == Tree.Kind.INTERFACE || kind == Tree.Kind.ANNOTATION_TYPE
                ? CompletionItemKind.Interface
                : kind == Tree.Kind.ENUM ? CompletionItemKind.Enum : CompletionItemKind.Class;
        i.detail = ownerType;
        setLabelDetails(i, null, ownerType);
        i.sortText = sortKey(Priority.IMPORTED_CLASS, i.label);
        if (addParentheses && i.kind == CompletionItemKind.Class) setInvocation(i, name, false);
        return i;
    }

    static CompletionItem compiledMember(Element member, boolean deprecated) {
        var item = new CompletionItem();
        item.label = member.getSimpleName().toString();
        item.kind = switch (member.getKind()) {
            case FIELD -> CompletionItemKind.Field;
            case ENUM_CONSTANT -> CompletionItemKind.EnumMember;
            default -> CompletionItemKind.Property;
        };
        item.detail = ElementSignatureHelper.simpleTypeName(member.asType());
        var value = member instanceof VariableElement field && field.getConstantValue() != null
                ? " = " + TypeNames.formatConstant(field.getConstantValue()) : null;
        setLabelDetails(item, value, item.detail);
        item.deprecated = deprecated;
        return item;
    }

    static CompletionItem compiledMethod(ExecutableElement method, byte[] classBytes,
            MethodTree sourceMethod, CompletionData data, boolean addParentheses) {
        var item = new CompletionItem();
        item.label = method.getSimpleName().toString();
        item.kind = CompletionItemKind.Method;
        String params;
        if (sourceMethod == null) {
            params = ElementSignatureHelper.methodParams(method, classBytes);
        } else {
            var rendered = new StringJoiner(", ", "(", ")");
            for (int i = 0; i < method.getParameters().size(); i++) {
                rendered.add(ElementSignatureHelper.simpleTypeName(method.getParameters().get(i).asType())
                        + " " + sourceMethod.getParameters().get(i).getName());
            }
            params = rendered.toString();
        }
        var returnType = ElementSignatureHelper.simpleTypeName(method.getReturnType());
        item.detail = returnType + " " + item.label + params + overloadSuffix(data.plusOverloads);
        setLabelDetails(item, params, returnType + overloadSuffix(data.plusOverloads));
        if (addParentheses) setInvocation(item, item.label,
                data.plusOverloads == 0 && method.getParameters().isEmpty());
        item.data = JsonHelper.GSON.toJsonTree(data);
        return item;
    }

    static CompletionItem indexedMember(IndexedMember member, String[] names,
            String compilerId, boolean addParentheses) {
        var item = new CompletionItem();
        item.label = member.name;
        item.kind = member.kind;
        item.detail = indexedDetail(member, names, indexMethodParams(member, names));
        String description = null;
        if ((member.kind == CompletionItemKind.Field || member.kind == CompletionItemKind.EnumMember)
                && member.returnType != null) {
            item.detail = member.returnType;
            description = member.returnType;
        }
        setLabelDetails(item, member.constantValue != null ? " = " + member.constantValue : null, description);
        qualify(item, member.name, addParentheses && member.kind == CompletionItemKind.Class);
        var data = new CompletionData();
        data.className = member.ownerType;
        data.memberName = member.name;
        data.compilerId = compilerId;
        item.data = JsonHelper.GSON.toJsonTree(data);
        return item;
    }

    static CompletionItem indexedMethod(List<IndexedMember> overloads, String[] names,
            String compilerId, boolean addParentheses) {
        var first = overloads.getFirst();
        var item = new CompletionItem();
        item.label = first.name;
        item.kind = CompletionItemKind.Method;
        var params = indexMethodParams(first, names);
        item.detail = indexedDetail(first, names, params);
        var returnType = first.returnType != null ? TypeNames.simpleName(first.returnType) : "void";
        setLabelDetails(item, params, returnType + overloadSuffix(overloads.size() - 1));
        if (addParentheses) setInvocation(item, first.name, overloads.size() == 1
                && (first.erasedParameterTypes == null || first.erasedParameterTypes.length == 0));
        var data = new CompletionData();
        data.className = first.ownerType;
        data.memberName = first.name;
        data.erasedParameterTypes = first.erasedParameterTypes == null ? new String[0] : first.erasedParameterTypes;
        data.plusOverloads = overloads.size() - 1;
        data.compilerId = compilerId;
        item.data = JsonHelper.GSON.toJsonTree(data);
        return item;
    }

    private static String indexMethodParams(IndexedMember member, String[] names) {
        var params = new StringJoiner(", ", "(", ")");
        var types = member.erasedParameterTypes;
        if (types != null) for (int i = 0; i < types.length; i++) {
            var name = names != null && i < names.length && names[i] != null ? names[i] : "arg" + i;
            params.add(TypeNames.simpleName(types[i]) + " " + name);
        }
        return params.toString();
    }

    private static String indexedDetail(IndexedMember member, String[] names, String params) {
        if (member.parameterNames != null || member.erasedParameterTypes == null
                || member.erasedParameterTypes.length == 0 || member.kind == CompletionItemKind.Field
                || names == null) return member.detail;
        if (member.kind == CompletionItemKind.Constructor) return TypeNames.simpleName(member.ownerType) + params;
        var returnType = member.returnType != null ? TypeNames.simpleName(member.returnType) : "void";
        return returnType + " " + member.name + params;
    }

    static CompletionItem annotationAttribute(IndexedMember member) {
        var item = new CompletionItem();
        item.label = member.name;
        item.kind = CompletionItemKind.Property;
        item.detail = member.declaredReturnType != null ? member.declaredReturnType : "";
        setLabelDetails(item, null, member.declaredReturnType);
        qualify(item, member.name + " = ", false);
        item.sortText = sortKey(Priority.SNIPPET, member.name);
        return item;
    }

    static void resolveDetail(CompletionItem item, CompletionData data, Tree tree) {
        if (!(tree instanceof MethodTree method)) return;
        item.detail = syntaxMethodSignature(method) + overloadSuffix(data.plusOverloads);
    }

    private static String syntaxMethodParameters(MethodTree method) {
        var params = new StringJoiner(", ", "(", ")");
        for (var p : method.getParameters()) params.add(p.getType() + " " + p.getName());
        return params.toString();
    }

    private static String syntaxMethodSignature(MethodTree method) {
        var signature = method.getReturnType() + " " + method.getName() + syntaxMethodParameters(method);
        if (!method.getThrows().isEmpty()) {
            var exceptions = new StringJoiner(", ");
            for (var exception : method.getThrows()) exceptions.add(exception.toString());
            signature += " throws " + exceptions;
        }
        return signature;
    }

    private static String overloadSuffix(int count) {
        return count > 0 ? " (+" + count + " overloads)" : "";
    }

    static void qualify(CompletionItem item, String name, boolean addParentheses) {
        item.insertText = name;
        item.insertTextFormat = InsertTextFormat.PlainText;
        if (addParentheses) setInvocation(item, name, false);
    }

    static String sortKey(int priority, String label) {
        var safe = label == null ? "" : label;
        return String.format("%03d:%s", priority, safe.toLowerCase());
    }

    static void setInvocation(CompletionItem item, String name, boolean noArguments) {
        item.insertText = name + (noArguments ? "()" : "($0)");
        item.insertTextFormat = noArguments ? InsertTextFormat.PlainText : InsertTextFormat.Snippet;
        if (!noArguments && item.kind == CompletionItemKind.Method) {
            item.command = new Command();
            item.command.command = "editor.action.triggerParameterHints";
            item.command.title = "Trigger Parameter Hints";
        }
    }

    static final class Priority {
        static final int SNIPPET = 0;
        static final int LOCAL = 1;
        static final int FIELD = 2;
        static final int METHOD = 3;
        static final int INHERITED_METHOD = 4;
        static final int IMPORTED_CLASS = 45;
        static final int NOT_IMPORTED_CLASS = 47;
        static final int KEYWORD = 95;
        static final int PACKAGE_MEMBER = 99;
    }
}
