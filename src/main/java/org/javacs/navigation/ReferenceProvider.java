package org.javacs.navigation;

import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.TypeElement;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.LombokHandler;
import org.javacs.LombokMetadataCache;
import org.javacs.lsp.Location;

public class ReferenceProvider {
    private final CompilerProvider compiler;
    private final LombokMetadataCache lombokCache;
    private final Path file;
    private final int line, column;

    public static final List<Location> NOT_SUPPORTED = List.of();

    public ReferenceProvider(
            CompilerProvider compiler, LombokMetadataCache lombokCache, Path file, int line, int column) {
        this.compiler = compiler;
        this.lombokCache = lombokCache;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<Location> find() {
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (element == null) return NOT_SUPPORTED;
            if (NavigationHelper.isType(element)) {
                var type = (TypeElement) element;
                var className = type.getQualifiedName().toString();
                task.close();
                return findTypeReferences(className);
            }
            if (NavigationHelper.isMember(element)) {
                var parentClass = (TypeElement) element.getEnclosingElement();
                var className = parentClass.getQualifiedName().toString();
                var memberName = element.getSimpleName().toString();
                if (memberName.equals("<init>")) {
                    memberName = parentClass.getSimpleName().toString();
                }
                task.close();

                // Handle both FIELD and RECORD_COMPONENT (record fields are like fields)
                if (element.getKind() == javax.lang.model.element.ElementKind.FIELD ||
                    element.getKind() == javax.lang.model.element.ElementKind.RECORD_COMPONENT) {
                    var references = new ArrayList<Location>();
                    references.addAll(findFieldReferencesScoped(className, memberName));
                    references.addAll(
                            LombokHandler.findAccessorReferences(
                                    compiler, className, memberName, lombokCache));

                    // For record components (or fields in record classes), also find the generated accessor method
                    boolean isRecordComponent = element.getKind() == javax.lang.model.element.ElementKind.RECORD_COMPONENT;
                    boolean isFieldInRecord = element.getKind() == javax.lang.model.element.ElementKind.FIELD &&
                                              parentClass.getKind() == javax.lang.model.element.ElementKind.RECORD;

                    if (isRecordComponent || isFieldInRecord) {
                        references.addAll(findMemberReferences(className, memberName));
                    }
                    return references;
                }
                return findMemberReferences(className, memberName);
            }
            if (NavigationHelper.isLocal(element)) {
                return findReferences(task);
            }
            return NOT_SUPPORTED;
        }
    }

    private List<Location> findTypeReferences(String className) {
        var files = compiler.findTypeReferences(className);
        if (files.length == 0) return List.of();
        try (var task = compiler.compile(files)) {
            return findReferences(task);
        }
    }

    private List<Location> findMemberReferences(String className, String memberName) {
        var files = compiler.findMemberReferences(className, memberName);
        if (files.length == 0) return List.of();
        try (var task = compiler.compile(files)) {
            return findReferencesForMember(task, className, memberName);
        }
    }

    private List<Location> findFieldReferencesScoped(String className, String memberName) {
        var files = compiler.findTypeReferences(className);
        var classFile = compiler.findTypeDeclaration(className);
        if (classFile != null && !classFile.equals(CompilerProvider.NOT_FOUND)) {
            var combined = new java.util.ArrayList<Path>();
            for (var f : files) {
                combined.add(f);
            }
            if (!combined.contains(classFile)) {
                combined.add(classFile);
            }
            files = combined.toArray(Path[]::new);
        }
        if (files.length == 0) return List.of();
        try (var task = compiler.compile(files)) {
            return findReferences(task);
        }
    }

    private List<Location> findReferences(CompileTask task) {
        var logger = java.util.logging.Logger.getLogger("main");
        var element = NavigationHelper.findElement(task, file, line, column);
        logger.info("ReferenceProvider.findReferences: element=" + element + ", kind=" + (element != null ? element.getKind() : "null"));
        var paths = new ArrayList<TreePath>();
        for (var root : task.roots) {
            new FindReferences(task.task, element).scan(root, paths);
        }
        logger.info("ReferenceProvider.findReferences: found " + paths.size() + " reference paths");
        var locations = new ArrayList<Location>();
        for (var p : paths) {
            locations.add(FindHelper.location(task, p));
        }
        logger.info("ReferenceProvider.findReferences: returning " + locations.size() + " locations");
        return locations;
    }

    private List<Location> findReferencesForMember(CompileTask task, String className, String memberName) {
        var logger = java.util.logging.Logger.getLogger("main");
        logger.info("ReferenceProvider.findReferencesForMember: searching for className=" + className + ", memberName=" + memberName);

        // Find the type element for the class
        var classElement = task.task.getElements().getTypeElement(className);
        if (classElement == null) {
            logger.info("ReferenceProvider.findReferencesForMember: class element not found");
            return List.of();
        }

        // Find the member (method or field) with the given name
        // For record accessors, prefer METHOD over FIELD (both have same name)
        javax.lang.model.element.Element targetElement = null;
        javax.lang.model.element.Element fallbackElement = null;

        for (var member : task.task.getElements().getAllMembers(classElement)) {
            if (member.getSimpleName().toString().equals(memberName)) {
                // Prefer METHOD (record accessor) over FIELD
                if (member.getKind() == javax.lang.model.element.ElementKind.METHOD) {
                    targetElement = member;
                    logger.info("ReferenceProvider.findReferencesForMember: found METHOD element=" + targetElement);
                    break;  // METHOD takes priority, stop searching
                }
                // Remember FIELD as fallback
                if (fallbackElement == null && member.getKind() == javax.lang.model.element.ElementKind.FIELD) {
                    fallbackElement = member;
                    logger.info("ReferenceProvider.findReferencesForMember: found FIELD element as fallback=" + fallbackElement);
                }
            }
        }

        // Use fallback if no method found
        if (targetElement == null) {
            targetElement = fallbackElement;
            if (targetElement != null) {
                logger.info("ReferenceProvider.findReferencesForMember: using fallback FIELD=" + targetElement);
            }
        }

        if (targetElement == null) {
            logger.info("ReferenceProvider.findReferencesForMember: member not found");
            return List.of();
        }

        // Search for references to this member
        var paths = new ArrayList<TreePath>();
        for (var root : task.roots) {
            new FindReferences(task.task, targetElement).scan(root, paths);
        }
        logger.info("ReferenceProvider.findReferencesForMember: found " + paths.size() + " reference paths");

        var locations = new ArrayList<Location>();
        for (var p : paths) {
            locations.add(FindHelper.location(task, p));
        }
        logger.info("ReferenceProvider.findReferencesForMember: returning " + locations.size() + " locations");
        return locations;
    }
}
