package org.javacs.markup;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.Range;

public class SemanticColors {
    public URI uri;
    public List<Range> statics = new ArrayList<>(), fields = new ArrayList<>();
}
