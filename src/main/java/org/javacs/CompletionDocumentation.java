package org.javacs;

import com.sun.source.tree.Tree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.javacs.FindHelper;
import org.javacs.ParseTask;
import org.javacs.lsp.MarkupContent;

public class CompletionDocumentation {
    private final CompilerProvider compiler;
    private final Map<String, Optional<MarkupContent>> cache = new HashMap<>();

    public CompletionDocumentation(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public Optional<MarkupContent> documentation(CompletionData data) {
        if (data == null || data.className == null) return Optional.empty();
        var key = cacheKey(data);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        var value = computeDocumentation(data);
        cache.put(key, value);
        return value;
    }

    private Optional<MarkupContent> computeDocumentation(CompletionData data) {
        var source = compiler.findAnywhere(data.className);
        if (source.isEmpty()) return Optional.empty();
        var parse = compiler.parse(source.get());
        var tree = findTree(parse, data);
        if (tree == null) return Optional.empty();
        var path = Trees.instance(parse.task).getPath(parse.root, tree);
        if (path == null) return Optional.empty();
        var docTree = DocTrees.instance(parse.task).getDocCommentTree(path);
        if (docTree == null) return Optional.empty();
        return Optional.of(MarkdownHelper.asMarkupContent(docTree));
    }

    private Tree findTree(ParseTask task, CompletionData data) {
        if (data.erasedParameterTypes != null) {
            return FindHelper.findMethod(task, data.className, data.memberName, data.erasedParameterTypes);
        }
        if (data.memberName != null) {
            return FindHelper.findField(task, data.className, data.memberName);
        }
        return FindHelper.findType(task, data.className);
    }

    private String cacheKey(CompletionData data) {
        var key = new StringBuilder();
        key.append(data.className);
        key.append("|");
        if (data.memberName != null) {
            key.append(data.memberName);
        }
        key.append("|");
        if (data.erasedParameterTypes != null) {
            for (var param : data.erasedParameterTypes) {
                key.append(param).append(",");
            }
        }
        return key.toString();
    }
}
