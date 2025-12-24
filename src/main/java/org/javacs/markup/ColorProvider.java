package org.javacs.markup;

import org.javacs.CompileTask;

public class ColorProvider {

    final CompileTask task;

    public ColorProvider(CompileTask task) {
        this.task = task;
    }

    public SemanticColors[] colors() {
        var colors = new SemanticColors[task.roots.size()];
        for (int i = 0; i < task.roots.size(); i++) {
            var root = task.roots.get(i);
            colors[i] = new SemanticColors();
            colors[i].uri = root.getSourceFile().toUri();
            new Colorizer(task.task).scan(root, colors[i]);
        }
        return colors;
    }
}
