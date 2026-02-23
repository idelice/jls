package org.javacs;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import org.javacs.lsp.CompletionItem;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.CompletionList;
import org.javacs.lsp.DiagnosticSeverity;
import org.javacs.lsp.InsertTextFormat;
import org.javacs.lsp.Location;
import org.javacs.lsp.MarkupContent;
import org.javacs.lsp.MarkupKind;
import org.javacs.lsp.ParameterInformation;
import org.javacs.lsp.SignatureHelp;
import org.javacs.lsp.SignatureInformation;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;

public final class LombokHandler {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("main");
    private static final java.util.regex.Pattern METHOD_WITH_PARAMS_PATTERN =
            java.util.regex.Pattern.compile("method\\s+(\\w+)\\s*\\(([^)]*)\\)");
    private static final java.util.regex.Pattern METHOD_IN_CLASS_PATTERN =
            java.util.regex.Pattern.compile("method\\s+(\\w+)\\s+in\\s+(?:class|enum)\\s+([\\w.]+)");
    private static final java.util.regex.Pattern FOUND_ARGUMENTS_PATTERN =
            java.util.regex.Pattern.compile("found:\\s*(.+?)(?:\\s+reason:|$)");
    private static final java.util.regex.Pattern LOCATION_CLASS_PATTERN =
            java.util.regex.Pattern.compile("location:.*(?:of type|class)\\s+([\\w.]+)");
    private static final java.util.regex.Pattern ENUM_CONSTRUCTOR_PATTERN =
            java.util.regex.Pattern.compile("constructor\\s+(\\w+)\\s+in enum\\s+([\\w.]+)");
    private static final java.util.regex.Pattern CLASS_CONSTRUCTOR_PATTERN =
            java.util.regex.Pattern.compile("constructor\\s+(\\w+)\\s+in class\\s+([\\w.]+)");
    private static final java.util.regex.Pattern VARIABLE_PATTERN =
            java.util.regex.Pattern.compile("variable\\s+(\\w+)");

    private LombokHandler() {}

    public static LombokMetadata metadataForClass(
            CompileTask task, String qualifiedName, LombokMetadataCache cache) {
        return metadataForClass(task, qualifiedName, cache, null);
    }

    private static LombokMetadata metadataForClass(
            CompileTask task, String qualifiedName, LombokMetadataCache cache, RequestCache requestCache) {
        if (shouldSkipLombokLookup(qualifiedName)) {
            if (requestCache != null) {
                requestCache.metadataByClassName.put(qualifiedName, null);
                LOG.fine(
                        () ->
                                "[lombok-cache] metadata skip scope="
                                        + requestCache.scope
                                        + " class="
                                        + qualifiedName);
            }
            return null;
        }
        if (cache != null) {
            var cached = metadataForClassName(cache, qualifiedName, task.roots, requestCache);
            if (cached != null) {
                enrichInheritedFields(task, qualifiedName, cached, requestCache);
                return cached;
            }
        }

        var classTree = findClassTreeCached(task.roots, qualifiedName, requestCache);
        if (classTree != null) {
            var metadata = LombokSupport.analyze(classTree);
            if (!LombokSupport.hasLombokAnnotations(metadata)) {
                if (requestCache != null) {
                    requestCache.metadataByClassName.put(qualifiedName, null);
                    LOG.fine(
                            () ->
                                    "[lombok-cache] metadata non-lombok scope="
                                            + requestCache.scope
                                            + " class="
                                            + qualifiedName);
                }
                return null;
            }

            enrichInheritedFields(task, qualifiedName, metadata, requestCache);
            if (requestCache != null) {
                requestCache.metadataByClassName.put(qualifiedName, metadata);
                LOG.fine(
                        () ->
                                "[lombok-cache] metadata store scope="
                                        + requestCache.scope
                                        + " class="
                                        + qualifiedName);
            }

            return metadata;
        }
        if (cache != null) {
            var fromSource = cache.getFromSource(qualifiedName);
            if (fromSource != null) {
                enrichInheritedFields(task, qualifiedName, fromSource, requestCache);
                if (requestCache != null) {
                    requestCache.metadataByClassName.put(qualifiedName, fromSource);
                    LOG.fine(
                            () ->
                                    "[lombok-cache] metadata store scope="
                                            + requestCache.scope
                                            + " class="
                                            + qualifiedName);
                }
            }
            if (requestCache != null && fromSource == null) {
                requestCache.metadataByClassName.put(qualifiedName, null);
            }
            return fromSource;
        }
        if (requestCache != null) {
            requestCache.metadataByClassName.put(qualifiedName, null);
        }
        return null;
    }

    private static void enrichInheritedFields(CompileTask task, String qualifiedName, LombokMetadata metadata) {
        enrichInheritedFields(task, qualifiedName, metadata, null);
    }

    private static void enrichInheritedFields(
            CompileTask task, String qualifiedName, LombokMetadata metadata, RequestCache requestCache) {
        // Skip semantic inherited-field enrichment when Lombok annotations cannot synthesize accessors.
        if (!(metadata.hasGetter || metadata.hasData || metadata.hasValue || metadata.hasSetter)) {
            return;
        }
        if (requestCache != null && requestCache.inheritedFieldsEnriched.contains(qualifiedName)) {
            LOG.fine(
                    () ->
                            "[lombok-cache] inherited-fields hit scope="
                                    + requestCache.scope
                                    + " class="
                                    + qualifiedName);
            return;
        }
        try {
            var typeElement = task.task.getElements().getTypeElement(qualifiedName);
            if (typeElement == null) {
                return;
            }
            var allMembers =
                    requestCache == null ? task.task.getElements().getAllMembers(typeElement) : allMembers(task, typeElement, requestCache);
            var inherited = new HashSet<String>();
            for (var member : allMembers) {
                if (member.getKind() != ElementKind.FIELD) {
                    continue;
                }
                var fieldName = member.getSimpleName().toString();
                if (!metadata.fieldsByName.containsKey(fieldName)) {
                    inherited.add(fieldName);
                }
            }
            if (!metadata.inheritedFieldNames.equals(inherited)) {
                metadata.inheritedFieldNames.clear();
                metadata.inheritedFieldNames.addAll(inherited);
                metadata.markIndexesDirty();
                metadata.rebuildGeneratedIndexes();
            }
            if (requestCache != null) {
                requestCache.inheritedFieldsEnriched.add(qualifiedName);
                LOG.fine(
                        () ->
                                "[lombok-cache] inherited-fields store scope="
                                        + requestCache.scope
                                        + " class="
                                        + qualifiedName
                                        + " count="
                                        + inherited.size());
            }
        } catch (RuntimeException e) {
            // Continue without inherited field enrichment if semantic analysis is unavailable.
        }
    }

    /**
     * Resolve the return type of a Lombok-generated method by analyzing field types.
     * Called when javac returns an ERROR type for a method that should be synthesized by Lombok.
     *
     * Example: errorTypeName = "com.example.Foo.getBar"
     * Returns: The TypeMirror for "Bar" (the type of the bar field)
     *
     * @param task compilation context
     * @param errorTypeName qualified name like "com.example.Foo.getBar" or "com.example.Foo.setBar"
     * @param cache metadata cache
     * @return the resolved TypeMirror, or null if not resolvable
     */
    public static javax.lang.model.type.TypeMirror resolveLombokGeneratedMethodType(
            CompileTask task, String errorTypeName, LombokMetadataCache cache) {
        var requestCache = new RequestCache("resolveLombokGeneratedMethodType:" + errorTypeName);
        return resolveLombokGeneratedMethodType(task, errorTypeName, cache, requestCache);
    }

    private static javax.lang.model.type.TypeMirror resolveLombokGeneratedMethodType(
            CompileTask task, String errorTypeName, LombokMetadataCache cache, RequestCache requestCache) {
        // Parse errorTypeName: last part is method name, rest is class name
        if (!errorTypeName.contains(".")) {
            return null;
        }

        var lastDotIndex = errorTypeName.lastIndexOf('.');
        var className = errorTypeName.substring(0, lastDotIndex);
        var methodName = errorTypeName.substring(lastDotIndex + 1);

        // Get the TypeElement for the class using semantic API
        var classElement = task.task.getElements().getTypeElement(className);
        if (classElement == null) {
            return null;
        }

        // Try to match the method name to a generated getter/setter
        // Getter: getXxx or isXxx -> field xxx
        // Setter: setXxx -> field xxx
        String fieldName = null;

        if (methodName.startsWith("get") && methodName.length() > 3) {
            fieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            fieldName = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        } else if (methodName.startsWith("set") && methodName.length() > 3) {
            fieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        }

        if (fieldName == null) {
            return null;
        }

        // Find the field in the class (including inherited fields)
        var allMembers = allMembers(task, classElement, requestCache);
        for (var member : allMembers) {
            if (member.getKind() == ElementKind.FIELD && member.getSimpleName().toString().equals(fieldName)) {
                var fieldElement = (VariableElement) member;
                var fieldType = fieldElement.asType();

                // For getter (getXxx or isXxx), return the field type
                if (methodName.startsWith("get") || methodName.startsWith("is")) {
                    return fieldType;
                }
            }
        }

        // For setter (setXxx), return void type
        if (methodName.startsWith("set")) {
            return task.task.getTypes().getNoType(javax.lang.model.type.TypeKind.VOID);
        }

        return null;
    }

    /**
     * Resolve the return type of a MethodInvocationTree that may call a Lombok-generated method.
     * This handles nested method chaining like: obj.getField1().getField2().getField3()
     *
     * @param task compilation context
     * @param invocation the method invocation tree
     * @param invocationPath the TreePath to the invocation
     * @param cache metadata cache
     * @return the resolved return type, or null if not a Lombok-generated method
     */
    public static javax.lang.model.type.TypeMirror resolveMethodInvocationReturnType(
            CompileTask task, MethodInvocationTree invocation, TreePath invocationPath, LombokMetadataCache cache) {
        var requestCache = new RequestCache("resolveMethodInvocationReturnType");
        return resolveMethodInvocationReturnType(task, invocation, invocationPath, cache, requestCache);
    }

    public static CompletionList completeSlf4jLogMemberSelect(
            CompileTask task,
            TreePath memberSelectPath,
            String partial,
            LombokMetadataCache cache,
            CompletionContext context) {
        if (memberSelectPath == null || !(memberSelectPath.getLeaf() instanceof MemberSelectTree)) {
            return null;
        }
        var requestCache =
                context != null
                        ? context.requestCache
                        : new RequestCache("completeSlf4jLogMemberSelect");
        var select = (MemberSelectTree) memberSelectPath.getLeaf();
        var expression = select.getExpression();

        if (!(expression instanceof IdentifierTree)
                || !"log".contentEquals(((IdentifierTree) expression).getName())) return null;

        var enclosingType = enclosingTypeElement(task, memberSelectPath);
        if (enclosingType == null) return null;

        var list = new ArrayList<CompletionItem>();
        addSlf4jLogMethodCompletions(task, enclosingType, partial, list, cache, requestCache);
        if (list.isEmpty()) return null;
        return new CompletionList(false, list);
    }

    private static javax.lang.model.type.TypeMirror resolveMethodInvocationReturnType(
            CompileTask task,
            MethodInvocationTree invocation,
            TreePath invocationPath,
            LombokMetadataCache cache,
            RequestCache requestCache) {
        var trees = Trees.instance(task.task);
        var methodSelect = invocation.getMethodSelect();

        LOG.fine("...resolveMethodInvocationReturnType called");

        // Only handle MemberSelectTree (e.g., obj.getField())
        if (!(methodSelect instanceof MemberSelectTree)) {
            LOG.fine("...methodSelect is not MemberSelectTree, returning null");
            return null;
        }

        var memberSelect = (MemberSelectTree) methodSelect;
        var methodName = memberSelect.getIdentifier().toString();
        LOG.fine(() -> "...method name: " + methodName);

        // Get the receiver (expression being called on)
        var receiverPath = new TreePath(invocationPath, memberSelect.getExpression());
        var receiverType = trees.getTypeMirror(receiverPath);
        if (receiverType == null) {
            LOG.fine("...receiver type is null");
            return null;
        }
        LOG.fine("...receiver type: " + receiverType + ", kind: " + receiverType.getKind());

        // If receiver type is ERROR, try to resolve it recursively
        if (receiverType.getKind() == TypeKind.ERROR) {
            LOG.fine("...receiver type is ERROR, attempting recursive resolution");
            // Check if the receiver is also a MethodInvocationTree that needs resolution
            if (memberSelect.getExpression() instanceof MethodInvocationTree) {
                LOG.fine("...receiver is also MethodInvocationTree, resolving recursively");
                receiverType = resolveMethodInvocationReturnType(
                        task, (MethodInvocationTree) memberSelect.getExpression(), receiverPath, cache, requestCache);
                if (receiverType == null) {
                    LOG.fine("...recursive resolution returned null");
                    return null;
                }
                LOG.fine("...recursive resolution succeeded: " + receiverType);
            } else {
                LOG.fine("...receiver is not MethodInvocationTree, cannot resolve ERROR type");
                return null;
            }
        }

        // Extract TypeElement from the receiver type
        TypeElement receiverTypeElement = null;
        if (receiverType instanceof DeclaredType) {
            var element = ((DeclaredType) receiverType).asElement();
            if (element instanceof TypeElement) {
                receiverTypeElement = (TypeElement) element;
            }
        }

        if (receiverTypeElement == null) {
            LOG.fine("...receiverTypeElement is null, cannot extract TypeElement");
            return null;
        }

        // Try Lombok metadata first for generated getters
        var className = receiverTypeElement.getQualifiedName().toString();
        LOG.fine(() -> "...receiver class name: " + className);
        var metadata = metadataForClass(task, className, cache, requestCache);
        LOG.fine(() -> "...Lombok metadata: " + (metadata != null ? "found" : "not found"));

        // If Lombok metadata exists, check if this is a generated getter
        if (metadata != null) {
            String fieldName = null;
            if (methodName.startsWith("get") && methodName.length() > 3) {
                fieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else if (methodName.startsWith("is") && methodName.length() > 2) {
                fieldName = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
            }

            LOG.fine("...field name extracted: " + fieldName);

            if (fieldName != null && metadata.isGeneratedGetter(methodName)) {
                LOG.fine("...method is confirmed as generated getter, looking for field");

                // Find the field and return its type
                var allMembers = allMembers(task, receiverTypeElement, requestCache);
                for (var member : allMembers) {
                    if (member.getKind() == ElementKind.FIELD && member.getSimpleName().toString().equals(fieldName)) {
                        var fieldType = ((VariableElement) member).asType();
                        LOG.fine(() -> "...found field, returning type: " + fieldType);
                        return fieldType;
                    }
                }
            }
        }

        // Fallback: Try to find the method using javac (for explicit methods or when Lombok metadata is unavailable)
        LOG.fine("...falling back to javac method resolution");
        var allMembers = allMembers(task, receiverTypeElement, requestCache);
        for (var member : allMembers) {
            if (member.getKind() == ElementKind.METHOD && member.getSimpleName().toString().equals(methodName)) {
                var method = (ExecutableElement) member;
                // Only match no-arg methods (getters don't have parameters)
                if (method.getParameters().isEmpty()) {
                    var returnType = method.getReturnType();
                    LOG.fine(() -> "...found method via javac, returning type: " + returnType);
                    return returnType;
                }
            }
        }

        LOG.fine("...method not found via Lombok or javac");
        return null;
    }

    private static TypeElement enclosingTypeElement(CompileTask task, TreePath path) {
        var trees = Trees.instance(task.task);
        for (var current = path; current != null; current = current.getParentPath()) {
            var kind = current.getLeaf().getKind();
            if (kind != Tree.Kind.CLASS && kind != Tree.Kind.RECORD && kind != Tree.Kind.ENUM) {
                continue;
            }
            var element = trees.getElement(current);
            if (element instanceof TypeElement) {
                return (TypeElement) element;
            }
        }
        return null;
    }

    public static void addCompletions(
            CompileTask task,
            TypeElement typeElement,
            String partial,
            List<CompletionItem> list,
            LombokMetadataCache cache) {
        var requestCache = new RequestCache("addCompletions:" + typeElement.getQualifiedName());
        addCompletions(task, typeElement, partial, list, cache, requestCache);
    }

    public static final class CompletionContext {
        private final RequestCache requestCache;

        private CompletionContext(RequestCache requestCache) {
            this.requestCache = requestCache;
        }
    }

    public static CompletionContext newCompletionContext(String scope) {
        return new CompletionContext(new RequestCache("completion:" + scope));
    }

    public static void addCompletions(
            CompileTask task,
            TypeElement typeElement,
            String partial,
            List<CompletionItem> list,
            LombokMetadataCache cache,
            CompletionContext context) {
        var requestCache = context != null ? context.requestCache : new RequestCache("addCompletions:" + typeElement.getQualifiedName());
        addCompletions(task, typeElement, partial, list, cache, requestCache);
    }

    private static void addCompletions(
            CompileTask task,
            TypeElement typeElement,
            String partial,
            List<CompletionItem> list,
            LombokMetadataCache cache,
            RequestCache requestCache) {
        var qualifiedName = typeElement.getQualifiedName().toString();
        var metadata = metadataForClass(task, qualifiedName, cache, requestCache);
        if (metadata == null) {
            return;
        }

        var sizeBefore = list.size();
        var existing = existingLabels(list);

        if (metadata.hasGetter || metadata.hasData || metadata.hasValue) {
            for (var getterName : metadata.getGeneratedGetterNames()) {
                if (existing.contains(getterName)) continue;
                if (StringSearch.matchesPartialName(getterName, partial)) {
                    var item = new CompletionItem();
                    item.label = getterName;
                    item.kind = CompletionItemKind.Method;
                    item.insertText = getterName + "()";
                    item.insertTextFormat = InsertTextFormat.PlainText;
                    item.documentation = generatedGetterDocumentation(getterName, metadata.fieldForGetter(getterName));
                    list.add(item);
                }
            }
        }

        if ((metadata.hasSetter || metadata.hasData) && !metadata.hasValue) {
            for (var setterName : metadata.getGeneratedSetterNames()) {
                if (existing.contains(setterName)) continue;
                if (StringSearch.matchesPartialName(setterName, partial)) {
                    var item = new CompletionItem();
                    item.label = setterName;
                    item.kind = CompletionItemKind.Method;
                    item.insertText = setterName + "($1)";
                    item.insertTextFormat = InsertTextFormat.Snippet;
                    item.documentation = generatedSetterDocumentation(setterName, metadata.fieldForSetter(setterName));
                    list.add(item);
                }
            }
        }

        if (metadata.hasToString && StringSearch.matchesPartialName("toString", partial)) {
            if (existing.contains("toString")) return;
            var item = new CompletionItem();
            item.label = "toString";
            item.kind = CompletionItemKind.Method;
            item.insertText = "toString()";
            item.insertTextFormat = InsertTextFormat.PlainText;
            item.documentation =
                    new MarkupContent(
                            MarkupKind.Markdown,
                            "```java\npublic String toString()\n```");
            list.add(item);
        }

        if (metadata.hasEqualsAndHashCode && StringSearch.matchesPartialName("equals", partial)) {
            if (existing.contains("equals")) return;
            var item = new CompletionItem();
            item.label = "equals";
            item.kind = CompletionItemKind.Method;
            item.insertText = "equals($1)";
            item.insertTextFormat = InsertTextFormat.Snippet;
            item.documentation =
                    new MarkupContent(
                            MarkupKind.Markdown,
                            "```java\npublic boolean equals(Object o)\n```");
            list.add(item);
        }

        if (metadata.hasEqualsAndHashCode && StringSearch.matchesPartialName("hashCode", partial)) {
            if (existing.contains("hashCode")) return;
            var item = new CompletionItem();
            item.label = "hashCode";
            item.kind = CompletionItemKind.Method;
            item.insertText = "hashCode()";
            item.insertTextFormat = InsertTextFormat.PlainText;
            item.documentation =
                    new MarkupContent(
                            MarkupKind.Markdown,
                            "```java\npublic int hashCode()\n```");
            list.add(item);
        }
        var added = list.size() - sizeBefore;
        LOG.fine(
                () ->
                        "[lombok-cache] completion direct scope="
                                + requestCache.scope
                                + " class="
                                + qualifiedName
                                + " partial="
                                + partial
                                + " added="
                                + added);

    }

    public static void addScopeCompletions(
            CompileTask task,
            TreePath path,
            String partial,
            List<CompletionItem> list,
            LombokMetadataCache cache) {
        var requestCache = new RequestCache("addScopeCompletions");
        // Find enclosing class
        while (path != null) {
            var kind = path.getLeaf().getKind();
            if (kind == Tree.Kind.CLASS || kind == Tree.Kind.RECORD) {
                var trees = Trees.instance(task.task);
                var enclosingElement = trees.getElement(path);
                if (enclosingElement instanceof TypeElement) {
                    var typeElement = (TypeElement) enclosingElement;
                    addCompletions(task, typeElement, partial, list, cache, requestCache);
                    addSlf4jLogVariableCompletion(task, typeElement, partial, list, cache, requestCache);
                }
                return;
            }
            path = path.getParentPath();
        }
    }

    public static void addSlf4jLogMethodCompletions(
            CompileTask task,
            TypeElement typeElement,
            String partial,
            List<CompletionItem> list,
            LombokMetadataCache cache) {
        var requestCache = new RequestCache("addSlf4jLogMethodCompletions:" + typeElement.getQualifiedName());
        addSlf4jLogMethodCompletions(task, typeElement, partial, list, cache, requestCache);
    }

    public static void addSlf4jLogMethodCompletions(
            CompileTask task,
            TypeElement typeElement,
            String partial,
            List<CompletionItem> list,
            LombokMetadataCache cache,
            CompletionContext context) {
        var requestCache =
                context != null
                        ? context.requestCache
                        : new RequestCache("addSlf4jLogMethodCompletions:" + typeElement.getQualifiedName());
        addSlf4jLogMethodCompletions(task, typeElement, partial, list, cache, requestCache);
    }

    private static void addSlf4jLogMethodCompletions(
            CompileTask task,
            TypeElement typeElement,
            String partial,
            List<CompletionItem> list,
            LombokMetadataCache cache,
            RequestCache requestCache) {
        var qualifiedName = typeElement.getQualifiedName().toString();
        var metadata = metadataForClass(task, qualifiedName, cache, requestCache);
        if (metadata == null || !metadata.hasSlf4j) {
            return;
        }

        var sizeBefore = list.size();
        var existing = existingLabels(list);

        // Slf4j log methods
        String[] methods = {"trace", "debug", "info", "warn", "error"};
        for (var method : methods) {
            if (existing.contains(method)) continue;
            if (!StringSearch.matchesPartialName(method, partial)) continue;
            var item = new CompletionItem();
            item.label = method;
            item.kind = CompletionItemKind.Method;
            item.insertText = method + "($1)";
            item.insertTextFormat = InsertTextFormat.Snippet;
            item.documentation =
                    new MarkupContent(
                            MarkupKind.Markdown,
                            "```java\npublic void " + method + "(String msg, Object... args)\n```");
            list.add(item);
        }
        var added = list.size() - sizeBefore;
        LOG.fine(
                () ->
                        "[lombok-cache] slf4j-method completion scope="
                                + requestCache.scope
                                + " class="
                                + qualifiedName
                                + " partial="
                                + partial
                                + " added="
                                + added);
    }

    public static void addStaticCompletions(
            CompileTask task,
            TypeElement typeElement,
            String partial,
            List<CompletionItem> list,
            LombokMetadataCache cache) {
        var requestCache = new RequestCache("addStaticCompletions:" + typeElement.getQualifiedName());
        addStaticCompletions(task, typeElement, partial, list, cache, requestCache);
    }

    public static void addStaticCompletions(
            CompileTask task,
            TypeElement typeElement,
            String partial,
            List<CompletionItem> list,
            LombokMetadataCache cache,
            CompletionContext context) {
        var requestCache =
                context != null
                        ? context.requestCache
                        : new RequestCache("addStaticCompletions:" + typeElement.getQualifiedName());
        addStaticCompletions(task, typeElement, partial, list, cache, requestCache);
    }

    private static void addStaticCompletions(
            CompileTask task,
            TypeElement typeElement,
            String partial,
            List<CompletionItem> list,
            LombokMetadataCache cache,
            RequestCache requestCache) {
        var qualifiedName = typeElement.getQualifiedName().toString();
        var metadata = metadataForClass(task, qualifiedName, cache, requestCache);
        if (metadata == null) {
            return;
        }

        var sizeBefore = list.size();
        var existing = existingLabels(list);

        if (metadata.hasBuilder && !metadata.explicitMethodNames.contains(metadata.builderMethodName)) {
            if (!existing.contains(metadata.builderMethodName)
                    && StringSearch.matchesPartialName(metadata.builderMethodName, partial)) {
                var item = new CompletionItem();
                item.label = metadata.builderMethodName;
                item.kind = CompletionItemKind.Method;
                item.insertText = metadata.builderMethodName + "()";
                item.insertTextFormat = InsertTextFormat.PlainText;
                list.add(item);
            }
        }

        // @Slf4j generates a static `log` field, so it should appear only in static/class completion.
        addSlf4jLogVariableCompletion(task, typeElement, partial, list, cache, requestCache);
        var added = list.size() - sizeBefore;
        LOG.fine(
                () ->
                        "[lombok-cache] completion static scope="
                                + requestCache.scope
                                + " class="
                                + qualifiedName
                                + " partial="
                                + partial
                                + " added="
                                + added);
    }

    public static CompletionList builderCompletionsForInvocation(
            CompileTask task,
            MethodInvocationTree invocation,
            String partial,
            LombokMetadataCache cache,
            CompilerProvider compiler) {
        var trees = Trees.instance(task.task);
        var select = invocation.getMethodSelect();
        if (!(select instanceof MemberSelectTree)) return null;
        var memberSelect = (MemberSelectTree) select;
        var methodName = memberSelect.getIdentifier().toString();

        var ownerPath = trees.getPath(task.root(), memberSelect.getExpression());
        if (ownerPath == null) return null;
        TypeElement ownerType = null;
        var ownerElement = trees.getElement(ownerPath);
        if (ownerElement instanceof TypeElement) {
            ownerType = (TypeElement) ownerElement;
        } else {
            ownerType = typeElement(trees.getTypeMirror(ownerPath));
        }
        LombokMetadata metadata = null;
        if (ownerType != null) {
            metadata = metadataForClass(task, ownerType.getQualifiedName().toString(), cache);
        } else if (memberSelect.getExpression() instanceof IdentifierTree && cache != null && compiler != null) {
            var simpleName = ((IdentifierTree) memberSelect.getExpression()).getName().toString();
            var qualified = resolveTypeName(task, compiler, task.root(), simpleName);
            if (qualified != null) {
                metadata = cache.getFromSource(qualified);
            }
        }
        if (metadata == null || !metadata.hasBuilder) return null;
        if (!methodName.equals(metadata.builderMethodName)) return null;
        if (metadata.explicitMethodNames.contains(metadata.builderMethodName)) return null;
        if (metadata.explicitInnerTypeNames.contains(metadata.builderClassName)) return null;

        var list = new ArrayList<CompletionItem>();
        for (var builderSetter : metadata.getBuilderSetterNames()) {
            if (!StringSearch.matchesPartialName(builderSetter, partial)) continue;
            var item = new CompletionItem();
            item.label = builderSetter;
            item.kind = CompletionItemKind.Method;
            item.insertText = builderSetter + "($1)";
            item.insertTextFormat = InsertTextFormat.Snippet;
            list.add(item);
        }
        if (!metadata.explicitBuilderMethodNames.contains(metadata.buildMethodName)
                && StringSearch.matchesPartialName(metadata.buildMethodName, partial)) {
            var item = new CompletionItem();
            item.label = metadata.buildMethodName;
            item.kind = CompletionItemKind.Method;
            item.insertText = metadata.buildMethodName + "()";
            item.insertTextFormat = InsertTextFormat.PlainText;
            list.add(item);
        }
        return new CompletionList(false, list);
    }

    private static MarkupContent generatedGetterDocumentation(String getterName, VariableTree field) {
        var returnType = field != null && field.getType() != null ? field.getType().toString() : "Object";
        var signature = "public " + returnType + " " + getterName + "()";
        var markdown = new StringBuilder("```java\n").append(signature).append("\n```");
        appendFieldContext(markdown, field);
        return new MarkupContent(MarkupKind.Markdown, markdown.toString());
    }

    private static MarkupContent generatedSetterDocumentation(String setterName, VariableTree field) {
        var paramType = field != null && field.getType() != null ? field.getType().toString() : "Object";
        var paramName = field != null ? field.getName().toString() : "value";
        var signature = "public void " + setterName + "(" + paramType + " " + paramName + ")";
        var markdown = new StringBuilder("```java\n").append(signature).append("\n```");
        appendFieldContext(markdown, field);
        return new MarkupContent(MarkupKind.Markdown, markdown.toString());
    }

    private static void appendFieldContext(StringBuilder markdown, VariableTree field) {
        if (field == null) {
            return;
        }
        markdown.append("\n\nField: `").append(field.getName()).append("`");
        var annotations = field.getModifiers().getAnnotations();
        if (!annotations.isEmpty()) {
            markdown.append("\n\nField annotations:");
            for (var annotation : annotations) {
                markdown.append("\n- `").append(annotation.toString()).append("`");
            }
        }
    }

    private static String resolveTypeName(
            CompileTask task, CompilerProvider compiler, CompilationUnitTree root, String simpleName) {
        var file = java.nio.file.Paths.get(root.getSourceFile().toUri());
        var packageName = FileStore.packageName(file);
        if (packageName != null && !packageName.isBlank()) {
            var candidate = packageName + "." + simpleName;
            if (compiler.findTypeDeclaration(candidate) != CompilerProvider.NOT_FOUND) {
                return candidate;
            }
        }
        for (var i : root.getImports()) {
            if (i.isStatic()) continue;
            var name = i.getQualifiedIdentifier().toString();
            if (name.endsWith("." + simpleName)) {
                return name;
            }
            if (name.endsWith(".*")) {
                var candidate = name.substring(0, name.length() - 2) + "." + simpleName;
                if (compiler.findTypeDeclaration(candidate) != CompilerProvider.NOT_FOUND) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public static List<Location> findGeneratedMemberLocations(CompileTask task, String className, String memberName) {
        var locations = new ArrayList<Location>();
        var classTree = findClassTree(task.roots, className);
        if (classTree == null) return locations;

        var metadata = LombokSupport.analyze(classTree);
        if (!LombokSupport.hasLombokAnnotations(metadata)) return locations;

        var trees = Trees.instance(task.task);
        var field = metadata.fieldForGetter(memberName);
        if (field == null) {
            field = metadata.fieldForSetter(memberName);
        }
        if (field == null) return locations;

        for (var root : task.roots) {
            var path = trees.getPath(root, field);
            if (path != null) {
                var fieldName = field.getName().toString();
                var location = FindHelper.location(task, path, fieldName);
                locations.add(location);
                break;
            }
        }
        return locations;
    }

    public static boolean shouldSuppressUnusedField(
            Element unusedEl, CompileTask task, LombokMetadataCache cache) {
        if (unusedEl == null) return false;

        // Record components are implicitly used (part of the implicit constructor)
        if (unusedEl.getKind() == ElementKind.RECORD_COMPONENT) {
            return true;
        }

        // Regular fields in Lombok classes are suppressed
        if (unusedEl.getKind() != ElementKind.FIELD) return false;
        var parent = unusedEl.getEnclosingElement();
        if (!(parent instanceof TypeElement)) return false;
        var className = ((TypeElement) parent).getQualifiedName().toString();
        if (className.isEmpty()) return false;
        var metadata = metadataForClass(task, className, cache);
        return metadata != null;
    }

    public static List<org.javacs.lsp.Diagnostic> constructorDiagnostics(
            CompileTask task, LombokMetadataCache cache, CompilationUnitTree root) {
        if (cache == null) return List.of();
        var diagnostics = new ArrayList<org.javacs.lsp.Diagnostic>();
        var trees = Trees.instance(task.task);
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitNewClass(com.sun.source.tree.NewClassTree tree, Void __) {
                var idPath = new TreePath(getCurrentPath(), tree.getIdentifier());
                var typeMirror = trees.getTypeMirror(idPath);
                if (!(typeMirror instanceof DeclaredType)) {
                    return super.visitNewClass(tree, __);
                }
                var typeElement = (TypeElement) ((DeclaredType) typeMirror).asElement();
                var className = typeElement.getQualifiedName().toString();
                var metadata = metadataForClass(task, className, cache);
                if (metadata == null) return super.visitNewClass(tree, __);

                var allowedCounts = constructorParamCounts(metadata);
                var builderAllArgsCount = builderAllArgsCount(metadata);
                var samePackage = samePackage(root, className);
                if (builderAllArgsCount >= 0 && samePackage) {
                    allowedCounts.add(builderAllArgsCount);
                }
                if (allowedCounts.isEmpty() && builderAllArgsCount < 0) return super.visitNewClass(tree, __);

                var argCount = tree.getArguments().size();
                if (allowedCounts.contains(argCount)) {
                    return super.visitNewClass(tree, __);
                }

                if (builderAllArgsCount >= 0 && !samePackage && argCount == builderAllArgsCount) {
                    var start = trees.getSourcePositions().getStartPosition(root, tree.getIdentifier());
                    var end = trees.getSourcePositions().getEndPosition(root, tree.getIdentifier());
                    var diagnostic = new org.javacs.lsp.Diagnostic();
                    diagnostic.severity = DiagnosticSeverity.Error;
                    diagnostic.code = "lombok.err.constructor_access";
                    diagnostic.message =
                            String.format(
                                    "'%s' is not public in '%s'. Cannot be accessed from outside package",
                                    constructorSignatureLabel(typeElement.getSimpleName().toString(), metadata, ConstructorKind.ALL),
                                    className);
                    diagnostic.range = range(root, start, end);
                    diagnostics.add(diagnostic);
                    return super.visitNewClass(tree, __);
                }

                var start = trees.getSourcePositions().getStartPosition(root, tree.getIdentifier());
                var end = trees.getSourcePositions().getEndPosition(root, tree.getIdentifier());
                var diagnostic = new org.javacs.lsp.Diagnostic();
                diagnostic.severity = DiagnosticSeverity.Error;
                diagnostic.code = "lombok.err.constructor_args";
                diagnostic.message = constructorErrorMessage(typeElement, metadata, builderAllArgsCount);
                diagnostic.range = range(root, start, end);
                diagnostics.add(diagnostic);
                return super.visitNewClass(tree, __);
            }
        }.scan(root, null);
        return diagnostics;
    }

    public static List<org.javacs.lsp.Diagnostic> builderConstructorDiagnostics(
            CompileTask task, CompilationUnitTree root) {
        var diagnostics = new ArrayList<org.javacs.lsp.Diagnostic>();
        var trees = Trees.instance(task.task);
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree tree, Void __) {
                var metadata = LombokSupport.analyze(tree);
                var className =
                        metadata != null && tree != null && tree.getSimpleName() != null
                                ? tree.getSimpleName().toString()
                                : "<unknown>";
                if (!metadata.hasBuilder
                        || !metadata.hasBuilderAnnotation
                        || !metadata.hasNoArgsConstructorAnnotation
                        || metadata.hasAllArgsConstructorAnnotation
                        || metadata.hasRequiredArgsConstructorAnnotation
                        || metadata.isRecord) {
                    return super.visitClass(tree, __);
                }

                var start = trees.getSourcePositions().getStartPosition(root, tree);
                var end = trees.getSourcePositions().getEndPosition(root, tree);
                for (var annotation : tree.getModifiers().getAnnotations()) {
                    var annotationType = annotation.getAnnotationType().toString();
                    var lastDot = annotationType.lastIndexOf('.');
                    var simpleName = lastDot >= 0 ? annotationType.substring(lastDot + 1) : annotationType;
                    if (simpleName.equals("Builder")) {
                        start = trees.getSourcePositions().getStartPosition(root, annotation);
                        end = trees.getSourcePositions().getEndPosition(root, annotation);
                        break;
                    }
                }
                var diagnostic = new org.javacs.lsp.Diagnostic();
                diagnostic.severity = DiagnosticSeverity.Error;
                diagnostic.code = "lombok.err.builder_constructor";
                diagnostic.message = "Lombok @Builder needs a proper constructor for this class";
                diagnostic.range = range(root, start, end);
                diagnostics.add(diagnostic);
                return super.visitClass(tree, __);
            }
        }.scan(root, null);
        return diagnostics;
    }

    public static SignatureHelp signatureHelpForMethod(
            CompileTask task,
            MemberSelectTree select,
            int activeParameter,
            LombokMetadataCache cache) {
        var trees = Trees.instance(task.task);
        var methodName = select.getIdentifier().toString();
        if (select.getExpression() instanceof MethodInvocationTree) {
            var builderHelp =
                    builderSignatureHelpForInvocation(
                            task, (MethodInvocationTree) select.getExpression(), methodName, activeParameter, cache);
            if (builderHelp != null) {
                return builderHelp;
            }
        }
        var path = trees.getPath(task.root(), select.getExpression());
        var type = typeElement(trees.getTypeMirror(path));
        if (type == null) return null;
        var metadata = metadataForClass(task, type.getQualifiedName().toString(), cache);
        if (metadata == null) return null;

        var fieldForSetter = metadata.fieldForSetter(methodName);
        if (fieldForSetter != null) {
            var info = new SignatureInformation();
            info.label = methodName;
            info.parameters = List.of(parameterInfo(fieldForSetter));
            addFancyLabel(info);
            return new SignatureHelp(List.of(info), 0, activeParameter);
        }
        var fieldForGetter = metadata.fieldForGetter(methodName);
        if (fieldForGetter != null) {
            var info = new SignatureInformation();
            info.label = methodName;
            info.parameters = List.of();
            addFancyLabel(info);
            return new SignatureHelp(List.of(info), 0, 0);
        }
        return null;
    }

    private static SignatureHelp builderSignatureHelpForInvocation(
            CompileTask task,
            MethodInvocationTree invocation,
            String methodName,
            int activeParameter,
            LombokMetadataCache cache) {
        var trees = Trees.instance(task.task);
        var select = invocation.getMethodSelect();
        if (!(select instanceof MemberSelectTree)) return null;
        var memberSelect = (MemberSelectTree) select;
        var builderName = memberSelect.getIdentifier().toString();
        var ownerPath = trees.getPath(task.root(), memberSelect.getExpression());
        if (ownerPath == null) return null;
        var ownerElement = trees.getElement(ownerPath);
        if (!(ownerElement instanceof TypeElement)) return null;
        var ownerType = (TypeElement) ownerElement;

        var metadata = metadataForClass(task, ownerType.getQualifiedName().toString(), cache);
        if (metadata == null || !metadata.hasBuilder) return null;
        if (!builderName.equals(metadata.builderMethodName)) return null;
        if (metadata.explicitMethodNames.contains(metadata.builderMethodName)) return null;
        if (metadata.explicitInnerTypeNames.contains(metadata.builderClassName)) return null;

        if (methodName.equals(metadata.buildMethodName)
                && !metadata.explicitBuilderMethodNames.contains(metadata.buildMethodName)) {
            var info = new SignatureInformation();
            info.label = metadata.buildMethodName;
            info.parameters = List.of();
            addFancyLabel(info);
            return new SignatureHelp(List.of(info), 0, 0);
        }
        if (metadata.isGeneratedBuilderMethod(methodName)) {
            var field = metadata.builderParamForName(methodName);
            if (field == null) return null;
            var info = new SignatureInformation();
            info.label = methodName;
            info.parameters = List.of(parameterInfo(field));
            addFancyLabel(info);
            return new SignatureHelp(List.of(info), 0, activeParameter);
        }
        return null;
    }

    public static SignatureHelp signatureHelpForConstructor(
            CompileTask task,
            com.sun.source.tree.NewClassTree tree,
            int activeParameter,
            LombokMetadataCache cache) {
        var trees = Trees.instance(task.task);
        var idPath = trees.getPath(task.root(), tree.getIdentifier());
        if (idPath == null) return null;
        var typeMirror = trees.getTypeMirror(idPath);
        if (!(typeMirror instanceof DeclaredType)) return null;
        var typeElement = (TypeElement) ((DeclaredType) typeMirror).asElement();
        var metadata = metadataForClass(task, typeElement.getQualifiedName().toString(), cache);
        if (metadata == null) return null;
        if (metadata.isRecord || metadata.hasExplicitConstructor) return null;

        var signatures = new ArrayList<SignatureInformation>();
        if (metadata.hasNoArgsConstructor) {
            var info = new SignatureInformation();
            info.label = typeElement.getSimpleName().toString();
            info.parameters = List.of();
            addFancyLabel(info);
            signatures.add(info);
        }
        if (metadata.hasRequiredArgsConstructor) {
            var info = constructorSignature(typeElement, metadata, ConstructorKind.REQUIRED);
            if (info != null) signatures.add(info);
        }
        if (metadata.hasAllArgsConstructor || metadata.hasData || metadata.hasValue) {
            var info = constructorSignature(typeElement, metadata, ConstructorKind.ALL);
            if (info != null) signatures.add(info);
        }
        if (signatures.isEmpty()) return null;
        return new SignatureHelp(signatures, 0, activeParameter);
    }

    private static boolean shouldFilterLombokSetterWithMatchingTypes(
            CompileTask task, LombokMetadataCache cache, javax.tools.Diagnostic<? extends JavaFileObject> d) {
        var message = d.getMessage(null);
        if (message == null) return false;

        String methodName = null;
        String className = null;
        List<String> paramTypes = null;

        // Try pattern 1: "method foo(...)" - when signature is shown
        var methodWithParamMatcher = METHOD_WITH_PARAMS_PATTERN.matcher(message);
        if (methodWithParamMatcher.find()) {
            methodName = methodWithParamMatcher.group(1);
            var params = methodWithParamMatcher.group(2);
            paramTypes = parseMethodParamTypes(params);
        } else {
            // Try pattern 2: "method foo in class Bar" - when signature not fully resolved
            var methodInClassMatcher = METHOD_IN_CLASS_PATTERN.matcher(message);
            if (methodInClassMatcher.find()) {
                methodName = methodInClassMatcher.group(1);
                className = methodInClassMatcher.group(2);
                // When params aren't in signature, check "found:" clause
                var foundMatcher = FOUND_ARGUMENTS_PATTERN.matcher(message);
                if (foundMatcher.find()) {
                    var found = foundMatcher.group(1).trim();
                    if (found.equals("no arguments")) {
                        paramTypes = new ArrayList<>();
                    } else {
                        paramTypes = parseMethodParamTypes(found);
                    }
                } else {
                    return false;
                }
            }
        }

        if (methodName == null || paramTypes == null) return false;

        // Extract class name if not already found
        if (className == null) {
            var classMatcher = LOCATION_CLASS_PATTERN.matcher(message);
            if (classMatcher.find()) {
                className = classMatcher.group(1);
            } else {
                className = enclosingClassNameAtDiagnostic(task, d);
                if (className == null) return false;
            }
        }
        className = resolveDiagnosticClassName(task, d, className);

        var metadata = cache.get(className, task.roots);
        if (metadata == null) return false;

        // Handle getters: 0 parameters - filter if Lombok would generate a getter
        if (paramTypes.isEmpty()) {
            return metadata.isGeneratedGetter(methodName);
        }

        // Handle setters: 1 parameter - filter if types match
        if (paramTypes.size() == 1) {
            var field = metadata.fieldForSetter(methodName);
            if (field == null) return false;

            var expectedType = field.getType().toString();
            var actualType = paramTypes.get(0);
            return typesMatch(expectedType, actualType);
        }

        return false;
    }

    private static boolean shouldFilterEnumConstructorError(
            CompileTask task, LombokMetadataCache cache, javax.tools.Diagnostic<? extends JavaFileObject> d) {
        var message = d.getMessage(null);
        if (message == null) return false;

        // Pattern 1: "constructor EnumName in enum com.example.EnumName cannot be applied"
        var enumMatcher = ENUM_CONSTRUCTOR_PATTERN.matcher(message);
        if (enumMatcher.find()) {
            var enumClassName = enumMatcher.group(2);
            var metadata = cache.get(enumClassName, task.roots);
            if (metadata == null) return false;

            // Filter if the enum has a Lombok constructor annotation
            return metadata.hasAllArgsConstructor ||
                   metadata.hasRequiredArgsConstructor ||
                   metadata.hasNoArgsConstructor ||
                   metadata.hasData ||
                   metadata.hasValue;
        }

        // Pattern 2: "constructor ClassName in class com.example.ClassName cannot be applied"
        // This handles @AllArgsConstructor, @RequiredArgsConstructor, @NoArgsConstructor on regular classes
        var classMatcher = CLASS_CONSTRUCTOR_PATTERN.matcher(message);
        if (classMatcher.find()) {
            var className = classMatcher.group(2);
            var metadata = cache.get(className, task.roots);
            if (metadata == null) return false;

            // Filter if the class has a Lombok constructor annotation
            return metadata.hasAllArgsConstructor ||
                   metadata.hasRequiredArgsConstructor ||
                   metadata.hasNoArgsConstructor ||
                   metadata.hasData ||
                   metadata.hasValue;
        }

        return false;
    }

    public static org.javacs.lsp.Diagnostic adjustDiagnostic(
            CompileTask task,
            LombokMetadataCache cache,
            javax.tools.Diagnostic<? extends JavaFileObject> d,
            CompilationUnitTree root) {
        if (cache == null) return null;
        var code = d.getCode();
        if (code == null || !code.startsWith("compiler.err.cant.resolve")) {
            return null;
        }
        var parsed = parseDiagnostic(task, d, d.getMessage(null));
        if (parsed == null || parsed.methodName == null || parsed.paramTypes == null || parsed.paramTypes.size() != 1) {
            return null;
        }
        if (parsed.className == null) return null;

        var className = parsed.className;
        var metadata = cache.get(className, task.roots);
        if (metadata == null) return null;

        var field = metadata.fieldForSetter(parsed.methodName);
        if (field == null) return null;

        var expectedType = field.getType().toString();
        var actualType = parsed.paramTypes.get(0);
        if (typesMatch(expectedType, actualType)) {
            return null;
        }
        // Avoid emitting type mismatch diagnostics here; string-based matching is too conservative
        // and causes false positives with inheritance. Let javac diagnostics stand.
        java.util.logging.Logger.getLogger("main")
                .fine("...skipping Lombok type mismatch diagnostic for " + className);
        return null;
    }

    public static boolean shouldFilterDiagnostic(
            CompileTask task, LombokMetadataCache cache, javax.tools.Diagnostic<? extends JavaFileObject> d) {
        var filterContext = newDiagnosticFilterContext(d != null ? d.getCode() : null);
        return shouldFilterDiagnostic(task, cache, d, filterContext);
    }

    public static DiagnosticFilterContext newDiagnosticFilterContext(String code) {
        var effectiveCode = code != null ? code : "<null>";
        return new DiagnosticFilterContext(new RequestCache("shouldFilterDiagnostic:" + effectiveCode));
    }

    public static void prefetchDiagnosticClassMetadata(
            CompileTask task,
            LombokMetadataCache cache,
            DiagnosticFilterContext context,
            java.util.Collection<String> classNames,
            CompilationUnitTree root) {
        if (cache == null || context == null || classNames == null || classNames.isEmpty()) return;
        var started = System.nanoTime();
        var prefetched = 0;
        for (var className : classNames) {
            if (className == null || className.isBlank()) continue;
            var resolved = normalizeDiagnosticClassName(task, root, className);
            metadataForClassName(cache, resolved, task.roots, context.requestCache);
            prefetched++;
        }
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;
        var prefetchedCount = prefetched;
        LOG.fine(
                () ->
                        "[lombok-prefetch] scope="
                                + context.requestCache.scope
                                + " classes="
                                + prefetchedCount
                                + " ms="
                                + elapsedMs);
    }

    public static boolean shouldFilterDiagnostic(
            CompileTask task,
            LombokMetadataCache cache,
            javax.tools.Diagnostic<? extends JavaFileObject> d,
            DiagnosticFilterContext context) {
        if (cache == null) return false;
        if (d == null) return false;
        var requestCache =
                context != null
                        ? context.requestCache
                        : new RequestCache("shouldFilterDiagnostic:" + d.getCode());
        var code = d.getCode();
        if (code == null) {
            return false;
        }
        // Handle "cannot apply symbol" errors for enum constructors with Lombok
        if (code.equals("compiler.err.cant.apply.symbol")) {
            return shouldFilterEnumConstructorError(task, cache, d) ||
                   shouldFilterLombokSetterWithMatchingTypes(task, cache, d);
        }
        // Handle "cannot resolve symbol" errors for Lombok-generated members
        if (!code.startsWith("compiler.err.cant.resolve")) {
            return false;
        }
        var message = d.getMessage(null);
        if (message == null) return false;
        var parsed = parseDiagnostic(task, d, message);
        if (parsed == null) return false;

        var quickDecision = quickDiagnosticFilterDecision(task, cache, d, requestCache, message);
        if (quickDecision == DiagnosticDecision.FILTER) {
            return true;
        }
        if (quickDecision == DiagnosticDecision.KEEP) {
            return false;
        }

        if (parsed.methodName != null && parsed.className != null) {
            if (parsed.paramTypes != null && parsed.paramTypes.isEmpty() && isDiagnosticInBooleanCondition(task, d)) {
                LOG.fine(
                        () ->
                                "[lombok-cache] keeping diagnostic in boolean condition class="
                                        + parsed.className
                                        + " method="
                                        + parsed.methodName);
                return false;
            }
            return isGeneratedMemberOfClass(
                    task, cache, parsed.className, parsed.methodName, parsed.paramTypes, requestCache);
        }

        if (parsed.variableName != null && parsed.className != null) {
            if ("log".equals(parsed.variableName)) {
                var metadata = cache.get(parsed.className, task.roots);
                if (metadata != null && metadata.hasSlf4j) {
                    return true;
                }
            }
            return isGeneratedMemberOfClass(task, cache, parsed.className, parsed.variableName, null, requestCache);
        }

        return false;
    }

    public static final class DiagnosticFilterContext {
        private final RequestCache requestCache;

        private DiagnosticFilterContext(RequestCache requestCache) {
            this.requestCache = requestCache;
        }
    }

    public static List<Location> findAccessorReferences(
            CompilerProvider compiler,
            String className,
            String fieldName,
            LombokMetadataCache cache) {
        var metadata = metadataForClassName(compiler, className, cache);
        if (metadata == null || !LombokSupport.hasLombokAnnotations(metadata)) return List.of();
        if (!metadata.fieldsByName.containsKey(fieldName)) return List.of();

        var getterName = metadata.getterNameForField(fieldName);
        var setterName = metadata.setterNameForField(fieldName);
        var accessorNames = new HashSet<String>();
        if (getterName != null) accessorNames.add(getterName);
        if (setterName != null) accessorNames.add(setterName);
        if (accessorNames.isEmpty()) return List.of();

        var files = new HashSet<java.nio.file.Path>();
        for (var accessorName : accessorNames) {
            for (var f : compiler.findMemberReferences(className, accessorName)) {
                files.add(f);
            }
        }
        if (files.isEmpty()) return List.of();

        var sources = new ArrayList<JavaFileObject>();
        for (var f : files) {
            sources.add(new SourceFileObject(f));
        }
        var classSource = compiler.findTypeDeclaration(className);
        if (classSource != null && !classSource.equals(CompilerProvider.NOT_FOUND)) {
            var alreadyIncluded =
                    sources.stream()
                            .anyMatch(source -> source.toUri().equals(classSource.toUri()));
            if (!alreadyIncluded) {
                sources.add(new SourceFileObject(classSource));
            }
        }

        try (var task = compiler.compile(sources)) {
            var trees = Trees.instance(task.task);
            var types = task.task.getTypes();
            var targetType = task.task.getElements().getTypeElement(className);
            if (targetType == null) return List.of();

            var paths = new ArrayList<TreePath>();
            for (var root : task.roots) {
                new AccessorReferenceScanner(trees, types, targetType, accessorNames, className)
                        .scan(root, paths);
            }
            var locations = new ArrayList<Location>();
            for (var path : paths) {
                locations.add(FindHelper.location(task, path));
            }
            return locations;
        }
    }

    private static LombokMetadata metadataForClassName(
            CompilerProvider compiler, String className, LombokMetadataCache cache) {
        if (cache != null) {
            var refreshed = cache.getFromSource(className);
            if (refreshed != null) return refreshed;
        }
        var sourceFile = compiler.findTypeDeclaration(className);
        if (sourceFile == null || sourceFile.equals(CompilerProvider.NOT_FOUND)) {
            return null;
        }
        var parse = compiler.parse(sourceFile);
        var classTree = findClassTree(List.of(parse.root), className);
        if (classTree == null) return null;
        var metadata = LombokSupport.analyze(classTree);
        if (!LombokSupport.hasLombokAnnotations(metadata)) return null;
        return metadata;
    }

    private static boolean isGeneratedMemberOfClass(
            CompileTask task,
            LombokMetadataCache cache,
            String className,
            String memberName,
            List<String> paramTypes,
            RequestCache requestCache) {
        var elements = task.task.getElements();
        var current = elements.getTypeElement(className);
        while (current != null) {
            var currentClassName = current.getQualifiedName().toString();
            var metadata = metadataForClassName(cache, currentClassName, task.roots, requestCache);
            if (metadata != null
                    && generatedMemberMatches(task, metadata, currentClassName, memberName, paramTypes, requestCache)) {
                return true;
            }
            var superType = current.getSuperclass();
            if (!(superType instanceof DeclaredType)) {
                break;
            }
            var superElement = ((DeclaredType) superType).asElement();
            if (!(superElement instanceof TypeElement)) {
                break;
            }
            current = (TypeElement) superElement;
            if (current.getQualifiedName().contentEquals("java.lang.Object")) {
                break;
            }
        }
        return false;
    }

    private static boolean generatedMemberMatches(
            CompileTask task,
            LombokMetadata metadata,
            String className,
            String memberName,
            List<String> paramTypes,
            RequestCache requestCache) {
        if (paramTypes != null) {
            if (paramTypes.isEmpty()) {
                if (metadata.isGeneratedGetter(memberName) || metadata.isGeneratedSpecialMethod(memberName)) {
                    return true;
                }
                if (metadata.hasBuilder
                        && memberName.equals(metadata.builderMethodName)
                        && !metadata.explicitMethodNames.contains(metadata.builderMethodName)
                        && !metadata.explicitInnerTypeNames.contains(metadata.builderClassName)) {
                    return true;
                }
                return metadata.hasBuilder
                        && memberName.equals(metadata.buildMethodName)
                        && !metadata.explicitBuilderMethodNames.contains(metadata.buildMethodName)
                        && !metadata.explicitInnerTypeNames.contains(metadata.builderClassName);
            }
            if (paramTypes.size() == 1) {
                var field = metadata.fieldForSetter(memberName);
                if (field != null
                        && (typesMatch(field.getType().toString(), paramTypes.get(0))
                                || isAssignableToField(task, className, field, paramTypes.get(0), requestCache))) {
                    return true;
                }
                if (metadata.hasBuilder
                        && !metadata.explicitInnerTypeNames.contains(metadata.builderClassName)
                        && metadata.isGeneratedBuilderMethod(memberName)) {
                    var builderField = metadata.builderParamForName(memberName);
                    if (builderField != null
                            && (typesMatch(builderField.getType().toString(), paramTypes.get(0))
                                    || isAssignableToField(task, className, builderField, paramTypes.get(0), requestCache))) {
                        return true;
                    }
                }
                return metadata.hasEqualsAndHashCode
                        && memberName.equals("equals")
                        && isObjectType(paramTypes.get(0));
            }
            return false;
        }
        return metadata.isGeneratedGetter(memberName)
                || metadata.isGeneratedSetter(memberName)
                || metadata.isGeneratedSpecialMethod(memberName)
                || (metadata.hasBuilder
                        && !metadata.explicitInnerTypeNames.contains(metadata.builderClassName)
                        && (memberName.equals(metadata.builderMethodName)
                                || metadata.isGeneratedBuilderMethod(memberName)));
    }

    private static LombokMetadata metadataForClassName(
            LombokMetadataCache cache, String className, List<CompilationUnitTree> roots) {
        return metadataForClassName(cache, className, roots, null);
    }

    private static final class ParsedDiagnostic {
        private final String className;
        private final String methodName;
        private final List<String> paramTypes;
        private final String variableName;

        private ParsedDiagnostic(String className, String methodName, List<String> paramTypes, String variableName) {
            this.className = className;
            this.methodName = methodName;
            this.paramTypes = paramTypes;
            this.variableName = variableName;
        }
    }

    private enum DiagnosticDecision {
        FILTER,
        KEEP,
        UNKNOWN
    }

    private static ParsedDiagnostic parseDiagnostic(
            CompileTask task,
            javax.tools.Diagnostic<? extends JavaFileObject> d,
            String message) {
        if (message == null) {
            return null;
        }
        var className = classNameFromMessage(task, d, message);
        var methodMatcher = METHOD_WITH_PARAMS_PATTERN.matcher(message);
        if (methodMatcher.find()) {
            var methodName = methodMatcher.group(1);
            var params = methodMatcher.group(2);
            return new ParsedDiagnostic(className, methodName, parseMethodParamTypes(params), null);
        }
        var variableMatcher = VARIABLE_PATTERN.matcher(message);
        if (variableMatcher.find()) {
            return new ParsedDiagnostic(className, null, null, variableMatcher.group(1));
        }
        return new ParsedDiagnostic(className, null, null, null);
    }

    private static DiagnosticDecision quickDiagnosticFilterDecision(
            CompileTask task,
            LombokMetadataCache cache,
            javax.tools.Diagnostic<? extends JavaFileObject> d,
            RequestCache requestCache,
            String message) {
        var parsed = parseDiagnostic(task, d, message);
        if (parsed == null) {
            return DiagnosticDecision.UNKNOWN;
        }
        if (parsed.methodName != null) {
            var className = parsed.className;
            if (className == null) return DiagnosticDecision.UNKNOWN;
            var metadata = metadataForClassName(cache, className, task.roots, requestCache);
            if (metadata == null) return DiagnosticDecision.UNKNOWN;
            List<String> paramTypes = parsed.paramTypes != null ? parsed.paramTypes : List.of();
            if (paramTypes.isEmpty()) {
                if (isDiagnosticInBooleanCondition(task, d)) {
                    return DiagnosticDecision.KEEP;
                }
                return generatedMemberMatchesQuick(metadata, parsed.methodName, paramTypes);
            }
            if (paramTypes.size() == 1) {
                var field = metadata.fieldForSetter(parsed.methodName);
                if (field != null && typesMatch(field.getType().toString(), paramTypes.get(0))) {
                    return DiagnosticDecision.FILTER;
                }
                if (field != null) {
                    return DiagnosticDecision.UNKNOWN;
                }
            }
            return DiagnosticDecision.UNKNOWN;
        }
        if (parsed.variableName != null) {
            var className = parsed.className;
            if (className == null) return DiagnosticDecision.UNKNOWN;
            var metadata = metadataForClassName(cache, className, task.roots, requestCache);
            if (metadata == null) return DiagnosticDecision.UNKNOWN;
            if ("log".equals(parsed.variableName) && metadata.hasSlf4j) {
                return DiagnosticDecision.FILTER;
            }
            if (generatedMemberMatchesQuick(metadata, parsed.variableName, null) == DiagnosticDecision.FILTER) {
                return DiagnosticDecision.FILTER;
            }
        }
        return DiagnosticDecision.UNKNOWN;
    }

    private static DiagnosticDecision generatedMemberMatchesQuick(
            LombokMetadata metadata, String memberName, List<String> paramTypes) {
        if (paramTypes != null) {
            if (paramTypes.isEmpty()) {
                if (metadata.isGeneratedGetter(memberName) || metadata.isGeneratedSpecialMethod(memberName)) {
                    return DiagnosticDecision.FILTER;
                }
                if (metadata.hasBuilder
                        && !metadata.explicitInnerTypeNames.contains(metadata.builderClassName)
                        && (memberName.equals(metadata.builderMethodName)
                                || memberName.equals(metadata.buildMethodName))) {
                    return DiagnosticDecision.FILTER;
                }
                return DiagnosticDecision.UNKNOWN;
            }
            if (paramTypes.size() == 1) {
                if (metadata.hasEqualsAndHashCode
                        && memberName.equals("equals")
                        && isObjectType(paramTypes.get(0))) {
                    return DiagnosticDecision.FILTER;
                }
                if (metadata.hasBuilder
                        && !metadata.explicitInnerTypeNames.contains(metadata.builderClassName)
                        && metadata.isGeneratedBuilderMethod(memberName)) {
                    var builderField = metadata.builderParamForName(memberName);
                    if (builderField != null
                            && typesMatch(builderField.getType().toString(), paramTypes.get(0))) {
                        return DiagnosticDecision.FILTER;
                    }
                }
                return DiagnosticDecision.UNKNOWN;
            }
            return DiagnosticDecision.UNKNOWN;
        }
        return (metadata.isGeneratedGetter(memberName)
                        || metadata.isGeneratedSetter(memberName)
                        || metadata.isGeneratedSpecialMethod(memberName)
                        || (metadata.hasBuilder
                                && !metadata.explicitInnerTypeNames.contains(metadata.builderClassName)
                                && (memberName.equals(metadata.builderMethodName)
                                        || metadata.isGeneratedBuilderMethod(memberName))))
                ? DiagnosticDecision.FILTER
                : DiagnosticDecision.KEEP;
    }

    private static String classNameFromMessage(
            CompileTask task,
            javax.tools.Diagnostic<? extends JavaFileObject> d,
            String message) {
        var classMatcher = LOCATION_CLASS_PATTERN.matcher(message);
        if (classMatcher.find()) {
            return resolveDiagnosticClassName(task, d, classMatcher.group(1));
        }
        var enclosing = enclosingClassNameAtDiagnostic(task, d);
        return enclosing != null ? resolveDiagnosticClassName(task, d, enclosing) : null;
    }

    private static LombokMetadata metadataForClassName(
            LombokMetadataCache cache,
            String className,
            List<CompilationUnitTree> roots,
            RequestCache requestCache) {
        if (requestCache != null) {
            if (requestCache.metadataByClassName.containsKey(className)) {
                var existing = requestCache.metadataByClassName.get(className);
                if (requestCache.loggedMetadataHits.add(className)) {
                    LOG.fine(
                            () ->
                                    "[lombok-cache] metadata hit scope="
                                            + requestCache.scope
                                            + " class="
                                            + className
                                            + " present="
                                            + (existing != null));
                }
                return existing;
            }
            if (requestCache.loggedMetadataMisses.add(className)) {
                LOG.fine(
                        () ->
                                "[lombok-cache] metadata miss scope="
                                        + requestCache.scope
                                        + " class="
                                        + className);
            }
        }
        if (cache == null) {
            return null;
        }
        var metadata = cache.get(className, roots);
        if (metadata == null && className.endsWith("Builder")) {
            var owner = className.substring(0, className.length() - "Builder".length());
            metadata = cache.get(owner, roots);
            if (metadata != null && !metadata.builderClassName.equals(simpleName(className))) {
                metadata = null;
            }
        }
        if (requestCache != null) {
            requestCache.metadataByClassName.put(className, metadata);
            final boolean present = metadata != null;
            if (requestCache.loggedMetadataStores.add(className)) {
                LOG.fine(
                        () ->
                                "[lombok-cache] metadata store scope="
                                        + requestCache.scope
                                        + " class="
                                        + className
                                        + " present="
                                        + present);
            }
        }
        return metadata;
    }

    private static String resolveDiagnosticClassName(
            CompileTask task, javax.tools.Diagnostic<? extends JavaFileObject> diagnostic, String rawClassName) {
        if (rawClassName == null || rawClassName.isBlank()) {
            return rawClassName;
        }
        if (rawClassName.contains(".")) {
            return rawClassName;
        }
        var elements = task.task.getElements();
        var direct = elements.getTypeElement(rawClassName);
        if (direct != null) {
            return direct.getQualifiedName().toString();
        }

        var path = findDiagnosticPath(task, diagnostic);
        if (path != null) {
            for (var current = path; current != null; current = current.getParentPath()) {
                if (!(current.getLeaf() instanceof ClassTree)) {
                    continue;
                }
                var classElement = Trees.instance(task.task).getElement(current);
                if (!(classElement instanceof TypeElement)) {
                    continue;
                }
                var type = (TypeElement) classElement;
                if (type.getSimpleName().contentEquals(rawClassName)) {
                    return type.getQualifiedName().toString();
                }
            }
        }

        var root = diagnosticRoot(task, diagnostic);
        if (root != null) {
            var rootPackage = root.getPackage();
            var packageName = rootPackage == null ? "" : rootPackage.getPackageName().toString();
            if (!packageName.isEmpty()) {
                var samePackageCandidate = packageName + "." + rawClassName;
                if (elements.getTypeElement(samePackageCandidate) != null) {
                    return samePackageCandidate;
                }
            }
        }
        return rawClassName;
    }

    private static String normalizeDiagnosticClassName(
            CompileTask task, CompilationUnitTree root, String className) {
        if (className == null || className.isBlank() || className.contains(".")) {
            return className;
        }
        var elements = task.task.getElements();
        var direct = elements.getTypeElement(className);
        if (direct != null) {
            return direct.getQualifiedName().toString();
        }
        var rootPackage = root.getPackage();
        var packageName = rootPackage == null ? "" : rootPackage.getPackageName().toString();
        if (packageName.isEmpty()) {
            return className;
        }
        var samePackage = packageName + "." + className;
        return elements.getTypeElement(samePackage) != null ? samePackage : className;
    }

    private static boolean isDiagnosticInBooleanCondition(
            CompileTask task, javax.tools.Diagnostic<? extends JavaFileObject> diagnostic) {
        var path = findDiagnosticPath(task, diagnostic);
        if (path == null) {
            return false;
        }
        for (var current = path; current != null; current = current.getParentPath()) {
            var parent = current.getParentPath();
            if (parent == null) {
                continue;
            }
            var leaf = parent.getLeaf();
            if (leaf instanceof com.sun.source.tree.IfTree) {
                var condition = ((com.sun.source.tree.IfTree) leaf).getCondition();
                if (isInsideSubtree(current, condition)) return true;
                continue;
            }
            if (leaf instanceof com.sun.source.tree.WhileLoopTree) {
                var condition = ((com.sun.source.tree.WhileLoopTree) leaf).getCondition();
                if (isInsideSubtree(current, condition)) return true;
                continue;
            }
            if (leaf instanceof com.sun.source.tree.DoWhileLoopTree) {
                var condition = ((com.sun.source.tree.DoWhileLoopTree) leaf).getCondition();
                if (isInsideSubtree(current, condition)) return true;
                continue;
            }
            if (leaf instanceof com.sun.source.tree.ForLoopTree) {
                var condition = ((com.sun.source.tree.ForLoopTree) leaf).getCondition();
                if (condition != null && isInsideSubtree(current, condition)) return true;
                continue;
            }
            if (leaf instanceof com.sun.source.tree.ConditionalExpressionTree) {
                var condition = ((com.sun.source.tree.ConditionalExpressionTree) leaf).getCondition();
                if (isInsideSubtree(current, condition)) return true;
            }
        }
        return false;
    }

    private static boolean isInsideSubtree(TreePath path, Tree subtreeRoot) {
        for (var current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() == subtreeRoot) {
                return true;
            }
        }
        return false;
    }

    private static TreePath findDiagnosticPath(
            CompileTask task, javax.tools.Diagnostic<? extends JavaFileObject> diagnostic) {
        var root = diagnosticRoot(task, diagnostic);
        if (root == null || diagnostic.getStartPosition() < 0) {
            return null;
        }
        return new FindNameAt(task).scan(root, diagnostic.getStartPosition());
    }

    private static CompilationUnitTree diagnosticRoot(
            CompileTask task, javax.tools.Diagnostic<? extends JavaFileObject> diagnostic) {
        if (diagnostic == null || diagnostic.getSource() == null) {
            return null;
        }
        var sourceUri = diagnostic.getSource().toUri();
        for (var root : task.roots) {
            if (root.getSourceFile().toUri().equals(sourceUri)) {
                return root;
            }
        }
        return null;
    }

    private static String enclosingClassNameAtDiagnostic(
            CompileTask task, javax.tools.Diagnostic<? extends JavaFileObject> diagnostic) {
        var path = findDiagnosticPath(task, diagnostic);
        if (path == null) return null;
        var trees = Trees.instance(task.task);
        for (var current = path; current != null; current = current.getParentPath()) {
            if (!(current.getLeaf() instanceof ClassTree)) continue;
            var element = trees.getElement(current);
            if (element instanceof TypeElement) {
                return ((TypeElement) element).getQualifiedName().toString();
            }
        }
        return null;
    }

    private static void addSlf4jLogVariableCompletion(
            CompileTask task,
            TypeElement typeElement,
            String partial,
            List<CompletionItem> list,
            LombokMetadataCache cache) {
        addSlf4jLogVariableCompletion(task, typeElement, partial, list, cache, null);
    }

    private static void addSlf4jLogVariableCompletion(
            CompileTask task,
            TypeElement typeElement,
            String partial,
            List<CompletionItem> list,
            LombokMetadataCache cache,
            RequestCache requestCache) {
        var qualifiedName = typeElement.getQualifiedName().toString();
        var metadata = metadataForClass(task, qualifiedName, cache, requestCache);
        if (metadata == null || !metadata.hasSlf4j) {
            return;
        }
        for (var existing : list) {
            if ("log".equals(existing.label)) {
                return;
            }
        }
        if (!StringSearch.matchesPartialName("log", partial)) {
            return;
        }
        var item = new CompletionItem();
        item.label = "log";
        item.kind = CompletionItemKind.Variable;
        item.insertText = "log";
        item.insertTextFormat = InsertTextFormat.PlainText;
        list.add(item);
        if (requestCache != null) {
            LOG.fine(
                    () ->
                            "[lombok-cache] slf4j-field completion scope="
                                    + requestCache.scope
                                    + " class="
                                    + qualifiedName
                                    + " partial="
                                    + partial
                                    + " added=1");
        }
    }

    private static String simpleName(String className) {
        var lastDot = className.lastIndexOf('.');
        if (lastDot >= 0) return className.substring(lastDot + 1);
        return className;
    }

    static boolean shouldSkipLombokLookup(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) return true;
        return qualifiedName.startsWith("java.")
                || qualifiedName.startsWith("javax.")
                || qualifiedName.startsWith("jdk.")
                || qualifiedName.startsWith("sun.");
    }

    private static Set<String> existingLabels(List<CompletionItem> list) {
        var existing = new HashSet<String>();
        for (var item : list) {
            if (item.label != null) {
                existing.add(item.label);
            }
        }
        return existing;
    }

    private static ClassTree findClassTree(List<CompilationUnitTree> roots, String qualifiedName) {
        LOG.fine(() -> "...findClassTree looking for: " + qualifiedName);

        // Try to find as a nested class first (e.g., OuterClass.InnerClass)
        // Work backwards through the qualified name to find outer/inner class boundaries
        var parts = qualifiedName.split("\\.");
        for (int i = parts.length - 1; i >= 0; i--) {
            var potentialPackage = String.join(".", java.util.Arrays.copyOfRange(parts, 0, i));
            var potentialClassPath = java.util.Arrays.copyOfRange(parts, i, parts.length);

            LOG.fine(
                    () ->
                            "...trying package: "
                                    + potentialPackage
                                    + ", class path: "
                                    + String.join(".", potentialClassPath));

            for (var root : roots) {
                var rootPackage = root.getPackage();
                var rootPackageName = rootPackage == null ? "" : rootPackage.getPackageName().toString();

                if (!rootPackageName.equals(potentialPackage)) continue;

                // Search for the outer class
                for (var typeDecl : root.getTypeDecls()) {
                    if (typeDecl.getKind() != Tree.Kind.CLASS && typeDecl.getKind() != Tree.Kind.ENUM) continue;

                    var classTree = (ClassTree) typeDecl;
                    if (classTree.getSimpleName().toString().equals(potentialClassPath[0])) {
                        // Found the outer class, now search for nested classes
                        var result = findNestedClass(classTree, potentialClassPath, 1);
                        if (result != null) {
                            LOG.fine(() -> "...found class: " + qualifiedName);
                            return result;
                        }
                    }
                }
            }
        }

        LOG.fine(() -> "...class not found: " + qualifiedName);
        return null;
    }

    private static ClassTree findClassTreeCached(
            List<CompilationUnitTree> roots, String qualifiedName, RequestCache requestCache) {
        if (requestCache == null) {
            return findClassTree(roots, qualifiedName);
        }
        if (requestCache.classTreeByQualifiedName.containsKey(qualifiedName)) {
            return requestCache.classTreeByQualifiedName.get(qualifiedName);
        }
        var found = findClassTree(roots, qualifiedName);
        requestCache.classTreeByQualifiedName.put(qualifiedName, found);
        return found;
    }

    private static ClassTree findNestedClass(ClassTree outerClass, String[] classPath, int index) {
        if (index >= classPath.length) {
            return outerClass;
        }

        var targetName = classPath[index];
        for (var member : outerClass.getMembers()) {
            if (member.getKind() == Tree.Kind.CLASS || member.getKind() == Tree.Kind.ENUM) {
                var nestedClass = (ClassTree) member;
                if (nestedClass.getSimpleName().toString().equals(targetName)) {
                    return findNestedClass(nestedClass, classPath, index + 1);
                }
            }
        }

        return null;
    }

    private static List<String> parseMethodParamTypes(String params) {
        if (params == null) return List.of();
        var trimmed = params.trim();
        if (trimmed.isEmpty()) return List.of();
        var parts = trimmed.split(",");
        var result = new ArrayList<String>();
        for (var part : parts) {
            var p = part.trim();
            if (!p.isEmpty()) {
                result.add(p);
            }
        }
        return result;
    }

    private static boolean typesMatch(String expectedType, String actualType) {
        // null is assignable to any reference type
        if ("<nulltype>".equals(actualType)) {
            return true;
        }
        var normalized_expected = normalizeType(expectedType);
        var normalized_actual = normalizeType(actualType);

        if (normalized_expected.equals(normalized_actual)) {
            return true;
        }

        // Check auto-boxing: primitive types are assignable to their boxed equivalents and vice versa
        return areBoxingEquivalent(normalized_expected, normalized_actual);
    }

    private static boolean isAssignableToField(
            CompileTask task, String ownerClassName, VariableTree field, String actualTypeName, RequestCache requestCache) {
        var assignableKey = ownerClassName + "|" + field.getType() + "|" + actualTypeName;
        var cachedAssignable = requestCache.assignableBySignature.get(assignableKey);
        if (cachedAssignable != null) {
            LOG.fine(
                    () ->
                            "[lombok-cache] assignable hit scope="
                                    + requestCache.scope
                                    + " key="
                                    + assignableKey
                                    + " value="
                                    + cachedAssignable);
            return cachedAssignable;
        }
        LOG.fine(() -> "[lombok-cache] assignable miss scope=" + requestCache.scope + " key=" + assignableKey);
        var expectedType = fieldTypeMirror(task, field);
        if (expectedType == null) {
            expectedType = resolveTypeMirror(task, field.getType().toString(), ownerClassName, requestCache);
        }
        if (expectedType == null) {
            requestCache.assignableBySignature.put(assignableKey, false);
            return false;
        }
        var actualType = resolveTypeMirror(task, actualTypeName, ownerClassName, requestCache);
        if (actualType == null) {
            requestCache.assignableBySignature.put(assignableKey, false);
            return false;
        }
        var assignable = task.task.getTypes().isAssignable(actualType, expectedType);
        requestCache.assignableBySignature.put(assignableKey, assignable);
        return assignable;
    }

    private static TypeMirror fieldTypeMirror(CompileTask task, VariableTree field) {
        var trees = Trees.instance(task.task);
        for (var root : task.roots) {
            var path = trees.getPath(root, field);
            if (path != null) {
                return trees.getTypeMirror(path);
            }
        }
        return null;
    }

    private static TypeMirror resolveTypeMirror(
            CompileTask task, String typeName, String ownerClassName, RequestCache requestCache) {
        if (typeName == null) {
            return null;
        }
        var cacheKey = ownerClassName + "|" + typeName;
        if (requestCache.resolvedTypeByName.containsKey(cacheKey)) {
            LOG.fine(
                    () ->
                            "[lombok-cache] type hit scope="
                                    + requestCache.scope
                                    + " key="
                                    + cacheKey);
            return requestCache.resolvedTypeByName.get(cacheKey);
        }
        LOG.fine(() -> "[lombok-cache] type miss scope=" + requestCache.scope + " key=" + cacheKey);
        if (typeName.endsWith("[]")) {
            var componentName = typeName.substring(0, typeName.length() - 2);
            var component = resolveTypeMirror(task, componentName, ownerClassName, requestCache);
            if (component == null) {
                requestCache.resolvedTypeByName.put(cacheKey, null);
                return null;
            }
            var type = task.task.getTypes().getArrayType(component);
            requestCache.resolvedTypeByName.put(cacheKey, type);
            return type;
        }
        TypeMirror resolved = null;
        switch (typeName) {
            case "byte":
                resolved = task.task.getTypes().getPrimitiveType(TypeKind.BYTE);
                break;
            case "short":
                resolved = task.task.getTypes().getPrimitiveType(TypeKind.SHORT);
                break;
            case "int":
                resolved = task.task.getTypes().getPrimitiveType(TypeKind.INT);
                break;
            case "long":
                resolved = task.task.getTypes().getPrimitiveType(TypeKind.LONG);
                break;
            case "float":
                resolved = task.task.getTypes().getPrimitiveType(TypeKind.FLOAT);
                break;
            case "double":
                resolved = task.task.getTypes().getPrimitiveType(TypeKind.DOUBLE);
                break;
            case "char":
                resolved = task.task.getTypes().getPrimitiveType(TypeKind.CHAR);
                break;
            case "boolean":
                resolved = task.task.getTypes().getPrimitiveType(TypeKind.BOOLEAN);
                break;
            case "void":
                resolved = task.task.getTypes().getNoType(TypeKind.VOID);
                break;
            case "<nulltype>":
                resolved = task.task.getTypes().getNullType();
                break;
            default:
                break;
        }
        if (resolved != null) {
            requestCache.resolvedTypeByName.put(cacheKey, resolved);
            return resolved;
        }
        var elements = task.task.getElements();
        var typeElement = elements.getTypeElement(typeName);
        if (typeElement != null) {
            resolved = typeElement.asType();
            requestCache.resolvedTypeByName.put(cacheKey, resolved);
            return resolved;
        }
        // Try same package as the owner class for simple names like `MSReference`
        if (ownerClassName != null && typeName.indexOf('.') < 0) {
            var lastDot = ownerClassName.lastIndexOf('.');
            if (lastDot > 0) {
                var packageName = ownerClassName.substring(0, lastDot);
                typeElement = elements.getTypeElement(packageName + "." + typeName);
                if (typeElement != null) {
                    resolved = typeElement.asType();
                    requestCache.resolvedTypeByName.put(cacheKey, resolved);
                    return resolved;
                }
            }
        }
        // Try java.lang for unqualified names
        if (typeName.indexOf('.') < 0) {
            typeElement = elements.getTypeElement("java.lang." + typeName);
            if (typeElement != null) {
                resolved = typeElement.asType();
                requestCache.resolvedTypeByName.put(cacheKey, resolved);
                return resolved;
            }
        }
        requestCache.resolvedTypeByName.put(cacheKey, null);
        return null;
    }

    private static List<? extends Element> allMembers(
            CompileTask task, TypeElement typeElement, RequestCache requestCache) {
        var className = typeElement.getQualifiedName().toString();
        var cached = requestCache.allMembersByClassName.get(className);
        if (cached != null) {
            LOG.fine(
                    () ->
                            "[lombok-cache] members hit scope="
                                    + requestCache.scope
                                    + " class="
                                    + className
                                    + " count="
                                    + cached.size());
            return cached;
        }
        var members = task.task.getElements().getAllMembers(typeElement);
        requestCache.allMembersByClassName.put(className, members);
        LOG.fine(
                () ->
                        "[lombok-cache] members miss/store scope="
                                + requestCache.scope
                                + " class="
                                + className
                                + " count="
                                + members.size());
        return members;
    }

    private static final class RequestCache {
        private final String scope;
        private final Map<String, List<? extends Element>> allMembersByClassName = new HashMap<>();
        private final Map<String, LombokMetadata> metadataByClassName = new HashMap<>();
        private final Map<String, ClassTree> classTreeByQualifiedName = new HashMap<>();
        private final Map<String, TypeMirror> resolvedTypeByName = new HashMap<>();
        private final Map<String, Boolean> assignableBySignature = new HashMap<>();
        private final Set<String> inheritedFieldsEnriched = new HashSet<>();
        private final Set<String> loggedMetadataMisses = new HashSet<>();
        private final Set<String> loggedMetadataStores = new HashSet<>();
        private final Set<String> loggedMetadataHits = new HashSet<>();

        private RequestCache(String scope) {
            this.scope = scope;
            LOG.fine(() -> "[lombok-cache] create scope=" + scope);
        }
    }

    /** Check if two types are equivalent under Java's auto-boxing rules */
    private static boolean areBoxingEquivalent(String type1, String type2) {
        var boxed1 = getBoxedEquivalent(type1);
        var boxed2 = getBoxedEquivalent(type2);

        // If either type doesn't have a boxing equivalent, they're not boxing-equivalent
        if (boxed1 == null || boxed2 == null) {
            return false;
        }

        // They're equivalent if their boxed forms match
        return boxed1.equals(boxed2);
    }

    /** Get the boxed equivalent of a primitive type, or null if not a primitive */
    private static String getBoxedEquivalent(String type) {
        // Remove array suffixes temporarily
        var arraySuffix = "";
        var baseType = type;
        while (baseType.endsWith("[]")) {
            arraySuffix += "[]";
            baseType = baseType.substring(0, baseType.length() - 2);
        }

        var boxed = switch(baseType) {
            case "int" -> "Integer";
            case "long" -> "Long";
            case "double" -> "Double";
            case "float" -> "Float";
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "char" -> "Character";
            case "short" -> "Short";
            case "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Character", "Short" -> baseType;
            default -> null;
        };

        return boxed != null ? boxed + arraySuffix : null;
    }

    private static String normalizeType(String typeName) {
        var type = typeName.replace(" ", "");
        type = type.replaceAll("<.*>", "");
        var arraySuffix = "";
        while (type.endsWith("[]")) {
            arraySuffix += "[]";
            type = type.substring(0, type.length() - 2);
        }
        var lastDot = type.lastIndexOf('.');
        var simple = lastDot >= 0 ? type.substring(lastDot + 1) : type;
        return simple + arraySuffix;
    }

    private static boolean isObjectType(String typeName) {
        var normalized = normalizeType(typeName);
        return normalized.equals("Object");
    }

    private static List<Integer> constructorParamCounts(LombokMetadata metadata) {
        if (metadata.isRecord || metadata.hasExplicitConstructor) {
            return List.of();
        }
        var counts = new HashSet<Integer>();
        if (metadata.hasNoArgsConstructor) {
            counts.add(0);
        }
        if (metadata.hasRequiredArgsConstructor
                && !(metadata.hasBuilder
                        && metadata.builderSource == LombokMetadata.BuilderSource.CLASS
                        && !metadata.hasRequiredArgsConstructorAnnotation)) {
            counts.add(requiredFieldCount(metadata));
        }
        if (metadata.hasAllArgsConstructor) {
            counts.add(allInstanceFieldCount(metadata));
        }
        return new ArrayList<>(counts);
    }

    private static int builderAllArgsCount(LombokMetadata metadata) {
        if (!metadata.hasBuilder) return -1;
        if (metadata.builderSource != LombokMetadata.BuilderSource.CLASS) return -1;
        if (metadata.hasExplicitConstructor) return -1;
        if (metadata.hasAllArgsConstructor || metadata.hasValue) return -1;
        return allInstanceFieldCount(metadata);
    }

    private static boolean samePackage(CompilationUnitTree root, String className) {
        var rootPackage = root.getPackageName() == null ? "" : root.getPackageName().toString();
        var classPackage = packageName(className);
        return Objects.equals(rootPackage, classPackage);
    }

    private static String packageName(String className) {
        var lastDot = className.lastIndexOf('.');
        if (lastDot < 0) return "";
        return className.substring(0, lastDot);
    }

    private static String constructorErrorMessage(
            TypeElement typeElement, LombokMetadata metadata, int builderAllArgsCount) {
        var name = typeElement.getSimpleName().toString();
        var signatures = new ArrayList<String>();
        if (metadata.hasNoArgsConstructor) {
            signatures.add(name + "()");
        }
        if (metadata.hasRequiredArgsConstructor
                && !(metadata.hasBuilder
                        && metadata.builderSource == LombokMetadata.BuilderSource.CLASS
                        && !metadata.hasRequiredArgsConstructorAnnotation)) {
            signatures.add(constructorSignatureLabel(name, metadata, ConstructorKind.REQUIRED));
        }
        if (metadata.hasAllArgsConstructor) {
            signatures.add(constructorSignatureLabel(name, metadata, ConstructorKind.ALL));
        } else if (builderAllArgsCount >= 0) {
            signatures.add(constructorSignatureLabel(name, metadata, ConstructorKind.ALL));
        }
        if (signatures.isEmpty()) {
            return String.format("Constructor %s cannot be applied to given arguments", name);
        }
        return "Available constructors: " + String.join(", ", signatures);
    }

    private static String constructorSignatureLabel(
            String name, LombokMetadata metadata, ConstructorKind kind) {
        var params = new ArrayList<String>();
        if (kind == ConstructorKind.ALL) {
            for (var field : metadata.allFields) {
                if (!isStatic(field)) {
                    params.add(field.getType() + " " + field.getName());
                }
            }
        } else {
            for (var field : metadata.allFields) {
                if (isRequiredField(field)) {
                    params.add(field.getType() + " " + field.getName());
                }
            }
        }
        return name + "(" + String.join(", ", params) + ")";
    }

    private static int requiredFieldCount(LombokMetadata metadata) {
        var count = 0;
        for (var field : metadata.allFields) {
            if (isRequiredField(field)) {
                count++;
            }
        }
        return count;
    }

    private static int allInstanceFieldCount(LombokMetadata metadata) {
        var count = 0;
        for (var field : metadata.allFields) {
            if (!isStatic(field)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isFinal(VariableTree field) {
        for (var mod : field.getModifiers().getFlags()) {
            if (mod.name().equals("FINAL")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStatic(VariableTree field) {
        for (var mod : field.getModifiers().getFlags()) {
            if (mod.name().equals("STATIC")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRequiredField(VariableTree field) {
        if (isStatic(field)) return false;
        if (field.getInitializer() != null) return false;
        if (isFinal(field)) return true;
        return hasNonNullAnnotation(field);
    }

    private static boolean hasNonNullAnnotation(VariableTree field) {
        for (var annotation : field.getModifiers().getAnnotations()) {
            var annotationType = annotation.getAnnotationType().toString();
            var lastDot = annotationType.lastIndexOf('.');
            var simpleName = lastDot >= 0 ? annotationType.substring(lastDot + 1) : annotationType;
            if (simpleName.equals("NonNull")) {
                return true;
            }
        }
        return false;
    }

    private enum ConstructorKind {
        REQUIRED,
        ALL
    }

    private static SignatureInformation constructorSignature(
            TypeElement typeElement, LombokMetadata metadata, ConstructorKind kind) {
        var info = new SignatureInformation();
        info.label = typeElement.getSimpleName().toString();
        var params = new ArrayList<ParameterInformation>();
        if (kind == ConstructorKind.ALL) {
            for (var field : metadata.allFields) {
                if (!isStatic(field)) {
                    params.add(parameterInfo(field));
                }
            }
        } else {
            for (var field : metadata.allFields) {
                if (isRequiredField(field)) {
                    params.add(parameterInfo(field));
                }
            }
        }
        info.parameters = params;
        addFancyLabel(info);
        return info;
    }

    private static ParameterInformation parameterInfo(VariableTree field) {
        var info = new ParameterInformation();
        info.label = field.getType() + " " + field.getName();
        return info;
    }

    private static void addFancyLabel(SignatureInformation info) {
        var join = new java.util.StringJoiner(", ");
        for (var p : info.parameters) {
            join.add(p.label);
        }
        info.label = info.label + "(" + join + ")";
    }

    private static TypeElement typeElement(javax.lang.model.type.TypeMirror type) {
        if (type instanceof DeclaredType) {
            var declared = (DeclaredType) type;
            return (TypeElement) declared.asElement();
        }
        if (type instanceof TypeVariable) {
            var variable = (TypeVariable) type;
            return typeElement(variable.getUpperBound());
        }
        return null;
    }

    private static Range range(CompilationUnitTree root, long start, long end) {
        var lines = root.getLineMap();
        var startLine = (int) lines.getLineNumber(start);
        var startColumn = (int) lines.getColumnNumber(start);
        var startPos = new Position(startLine - 1, startColumn - 1);
        var endLine = (int) lines.getLineNumber(end);
        var endColumn = (int) lines.getColumnNumber(end);
        var endPos = new Position(endLine - 1, endColumn - 1);
        return new Range(startPos, endPos);
    }

    private static final class AccessorReferenceScanner extends TreePathScanner<Void, List<TreePath>> {
        private final Trees trees;
        private final Types types;
        private final TypeElement targetType;
        private final Set<String> accessorNames;
        private final String targetClassName;

        AccessorReferenceScanner(
                Trees trees, Types types, TypeElement targetType, Set<String> accessorNames, String targetClassName) {
            this.trees = trees;
            this.types = types;
            this.targetType = targetType;
            this.accessorNames = accessorNames;
            this.targetClassName = targetClassName;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, List<TreePath> paths) {
            var select = tree.getMethodSelect();
            if (select instanceof MemberSelectTree) {
                var memberSelect = (MemberSelectTree) select;
                var name = memberSelect.getIdentifier().toString();
                if (accessorNames.contains(name) && isReceiverMatch(memberSelect)) {
                    paths.add(new TreePath(getCurrentPath(), memberSelect));
                }
            } else if (select instanceof IdentifierTree) {
                var ident = (IdentifierTree) select;
                var name = ident.getName().toString();
                if (accessorNames.contains(name) && isEnclosingTypeMatch()) {
                    paths.add(new TreePath(getCurrentPath(), ident));
                }
            }
            return super.visitMethodInvocation(tree, paths);
        }

        private boolean isReceiverMatch(MemberSelectTree select) {
            var exprPath = new TreePath(new TreePath(getCurrentPath(), select), select.getExpression());
            var type = trees.getTypeMirror(exprPath);
            if (type == null || type.getKind() != TypeKind.DECLARED) return false;
            var declared = (DeclaredType) type;
            if (targetType == null) return false;
            return types.isSameType(types.erasure(declared), types.erasure(targetType.asType()));
        }

        private boolean isEnclosingTypeMatch() {
            var path = getCurrentPath();
            while (path != null) {
                var leaf = path.getLeaf();
                if (leaf instanceof ClassTree) {
                    var typeEl = (TypeElement) trees.getElement(path);
                    if (typeEl == null) return false;
                    if (targetType == null) return false;
                    return types.isSameType(
                            types.erasure(typeEl.asType()), types.erasure(targetType.asType()));
                }
                path = path.getParentPath();
            }
            return false;
        }
    }
}
