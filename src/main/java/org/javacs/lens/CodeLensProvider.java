package org.javacs.lens;

import java.util.ArrayList;
import java.util.List;
import org.javacs.ParseTask;
import org.javacs.lsp.CodeLens;

public class CodeLensProvider {

    public static List<CodeLens> find(ParseTask task) {
        var list = new ArrayList<CodeLens>();
        new FindCodeLenses(task.task).scan(task.root, list);
        return list;
    }
}
