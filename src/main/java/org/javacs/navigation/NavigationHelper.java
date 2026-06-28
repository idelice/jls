package org.javacs.navigation;

import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import org.javacs.CompileTask;
import org.javacs.FindNameAt;

public class NavigationHelper {
    private static final Logger LOG = Logger.getLogger("main");

    public static Element findElement(CompileTask task, Path file, int line, int column) {
        for (var root : task.roots) {
            if (root.getSourceFile().toUri().equals(file.toUri())) {
                var trees = task.trees;
                var cursor = root.getLineMap().getPosition(line, column);
                LOG.info("[nav] findElement " + file.getFileName() + ":" + line + ":" + column + " cursor=" + cursor + " roots=" + task.roots.size());
                var path = new FindNameAt(task).scan(root, cursor);
                if (path == null) {
                    LOG.info("[nav] findElement -> null (no path found at cursor=" + cursor + ")");
                    return null;
                }
                var element = trees.getElement(path);
                LOG.info("[nav] findElement -> " + (element != null ? element.getKind() + " " + element : "null"));
                return element;
            }
        }
        throw new RuntimeException("file not found");
    }

    public static boolean isLocal(Element element) {
        if (element.getModifiers().contains(Modifier.PRIVATE)) {
            return true;
        }
        switch (element.getKind()) {
            case EXCEPTION_PARAMETER:
            case LOCAL_VARIABLE:
            case PARAMETER:
            case TYPE_PARAMETER:
                return true;
            default:
                return false;
        }
    }

    public static boolean isMember(Element element) {
        switch (element.getKind()) {
            case ENUM_CONSTANT:
            case FIELD:
            case METHOD:
            case CONSTRUCTOR:
            case RECORD_COMPONENT:
                return true;
            default:
                return false;
        }
    }

    public static boolean isType(Element element) {
        switch (element.getKind()) {
            case ANNOTATION_TYPE:
            case CLASS:
            case ENUM:
            case INTERFACE:
                return true;
            default:
                return false;
        }
    }
}
