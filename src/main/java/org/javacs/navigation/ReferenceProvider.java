package org.javacs.navigation;

import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.LombokHandler;
import org.javacs.LombokMetadataCache;
import org.javacs.lsp.Location;

public class ReferenceProvider {
    private static final Logger LOG = Logger.getLogger("main");
    private static final AtomicLong REQUEST_IDS = new AtomicLong();

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
        var requestId = REQUEST_IDS.incrementAndGet();
        var startedAt = System.nanoTime();
        logFine(
                requestId,
                "start",
                "file=" + file.getFileName() + ", line=" + line + ", column=" + column);
        try (var task = compiler.compile(file)) {
            var resolveStarted = System.nanoTime();
            var element = NavigationHelper.findElement(task, file, line, column);
            logFine(
                    requestId,
                    "resolveElement",
                    elapsedMs(resolveStarted),
                    "element=" + element + ", kind=" + (element != null ? element.getKind() : "null"));
            if (element == null) return NOT_SUPPORTED;
            if (NavigationHelper.isType(element)) {
                var type = (TypeElement) element;
                var className = type.getQualifiedName().toString();
                task.close();
                return findTypeReferences(requestId, className);
            }
            if (NavigationHelper.isMember(element)) {
                var parentClass = (TypeElement) element.getEnclosingElement();
                var className = parentClass.getQualifiedName().toString();
                var memberName = element.getSimpleName().toString();
                String[] erasedParameterTypes = null;
                if (element instanceof ExecutableElement method) {
                    erasedParameterTypes = FindHelper.erasedParameterTypes(task, method);
                }

                // For constructors, find constructor calls (which are type references)
                if (memberName.equals("<init>")) {
                    task.close();
                    return findTypeReferences(requestId, className);
                }

                task.close();

                // Handle both FIELD and RECORD_COMPONENT (record fields are like fields)
                if (element.getKind() == javax.lang.model.element.ElementKind.FIELD ||
                    element.getKind() == javax.lang.model.element.ElementKind.RECORD_COMPONENT) {
                    var references = new ArrayList<Location>();
                    references.addAll(findFieldReferencesScoped(requestId, className, memberName));
                    var accessorStarted = System.nanoTime();
                    references.addAll(
                            LombokHandler.findAccessorReferences(
                                    compiler, className, memberName, lombokCache));
                    logFine(
                            requestId,
                            "lombokAccessorReferences",
                            elapsedMs(accessorStarted),
                            "className=" + className + ", memberName=" + memberName);

                    // For record components (or fields in record classes), also find the generated accessor method
                    boolean isRecordComponent = element.getKind() == javax.lang.model.element.ElementKind.RECORD_COMPONENT;
                    boolean isFieldInRecord = element.getKind() == javax.lang.model.element.ElementKind.FIELD &&
                                              parentClass.getKind() == javax.lang.model.element.ElementKind.RECORD;

                    if (isRecordComponent || isFieldInRecord) {
                        references.addAll(findMemberReferences(requestId, className, memberName, null));
                    }
                    return references;
                }
                return findMemberReferences(requestId, className, memberName, erasedParameterTypes);
            }
            if (NavigationHelper.isLocal(element)) {
                return findReferences(requestId, task);
            }
            return NOT_SUPPORTED;
        } finally {
            logFine(requestId, "end", elapsedMs(startedAt), "file=" + file.getFileName());
        }
    }

    private List<Location> findTypeReferences(long requestId, String className) {
        var findCandidatesStarted = System.nanoTime();
        var files = compiler.findTypeReferences(className);
        logFine(
                requestId,
                "findTypeReferenceCandidates",
                elapsedMs(findCandidatesStarted),
                "className=" + className + ", files=" + files.length);
        if (files.length == 0) return List.of();
        var compileStarted = System.nanoTime();
        try (var task = compiler.compile(files)) {
            logFine(
                    requestId,
                    "compileTypeReferenceCandidates",
                    elapsedMs(compileStarted),
                    "className=" + className + ", files=" + files.length);
            return findReferences(requestId, task);
        }
    }

    private List<Location> findMemberReferences(long requestId, String className, String memberName) {
        return findMemberReferences(requestId, className, memberName, null);
    }

    private List<Location> findMemberReferences(
            long requestId, String className, String memberName, String[] erasedParameterTypes) {
        var findCandidatesStarted = System.nanoTime();
        var files = compiler.findMemberReferences(className, memberName);
        logFine(
                requestId,
                "findMemberReferenceCandidates",
                elapsedMs(findCandidatesStarted),
                "className=" + className + ", memberName=" + memberName + ", files=" + files.length);
        if (files.length == 0) return List.of();
        var compileStarted = System.nanoTime();
        try (var task = compiler.compile(files)) {
            logFine(
                    requestId,
                    "compileMemberReferenceCandidates",
                    elapsedMs(compileStarted),
                    "className=" + className + ", memberName=" + memberName + ", files=" + files.length);
            return findReferencesForMember(requestId, task, className, memberName, erasedParameterTypes);
        }
    }

    private List<Location> findFieldReferencesScoped(long requestId, String className, String memberName) {
        var findCandidatesStarted = System.nanoTime();
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
        logFine(
                requestId,
                "findFieldReferenceCandidatesScoped",
                elapsedMs(findCandidatesStarted),
                "className=" + className + ", memberName=" + memberName + ", files=" + files.length);
        if (files.length == 0) return List.of();
        var compileStarted = System.nanoTime();
        try (var task = compiler.compile(files)) {
            logFine(
                    requestId,
                    "compileFieldReferenceCandidatesScoped",
                    elapsedMs(compileStarted),
                    "className=" + className + ", memberName=" + memberName + ", files=" + files.length);
            return findReferences(requestId, task);
        }
    }

    private List<Location> findReferences(long requestId, CompileTask task) {
        var resolveStarted = System.nanoTime();
        var element = NavigationHelper.findElement(task, file, line, column);
        logFine(
                requestId,
                "resolveElementInBatch",
                elapsedMs(resolveStarted),
                "element=" + element + ", kind=" + (element != null ? element.getKind() : "null"));
        var paths = new ArrayList<TreePath>();
        var scanStarted = System.nanoTime();
        for (var root : task.roots) {
            new FindReferences(task.task, element).scan(root, paths);
        }
        logFine(
                requestId,
                "scanReferencePaths",
                elapsedMs(scanStarted),
                "roots=" + task.roots.size() + ", paths=" + paths.size());
        var locations = new ArrayList<Location>();
        var locationsStarted = System.nanoTime();
        for (var p : paths) {
            locations.add(FindHelper.location(task, p));
        }
        logFine(
                requestId,
                "convertReferenceLocations",
                elapsedMs(locationsStarted),
                "locations=" + locations.size());
        return locations;
    }

    private List<Location> findReferencesForMember(
            long requestId,
            CompileTask task,
            String className,
            String memberName,
            String[] erasedParameterTypes) {
        logFine(
                requestId,
                "resolveMemberTarget",
                "className=" + className + ", memberName=" + memberName);

        // Find the type element for the class
        var classLookupStarted = System.nanoTime();
        var classElement = task.task.getElements().getTypeElement(className);
        if (classElement == null) {
            logFine(requestId, "resolveMemberTargetClassMiss", elapsedMs(classLookupStarted), className);
            return List.of();
        }
        logFine(
                requestId,
                "resolveMemberTargetClass",
                elapsedMs(classLookupStarted),
                "className=" + className);

        javax.lang.model.element.Element targetElement = null;
        javax.lang.model.element.Element fallbackElement = null;
        var memberLookupStarted = System.nanoTime();

        if (erasedParameterTypes != null) {
            targetElement = FindHelper.findMethod(task, className, memberName, erasedParameterTypes);
            if (targetElement != null) {
                logFine(
                        requestId,
                        "resolveMemberTargetSignature",
                        elapsedMs(memberLookupStarted),
                        targetElement.toString());
            }
        }

        if (targetElement == null) {
            for (var member : classElement.getEnclosedElements()) {
                if (!member.getSimpleName().toString().equals(memberName)) {
                    continue;
                }
                if (member.getKind() == javax.lang.model.element.ElementKind.METHOD) {
                    targetElement = member;
                    break;
                }
                if (fallbackElement == null
                        && (member.getKind() == javax.lang.model.element.ElementKind.FIELD
                                || member.getKind() == javax.lang.model.element.ElementKind.RECORD_COMPONENT)) {
                    fallbackElement = member;
                }
            }
        }

        if (targetElement == null && fallbackElement == null) {
            for (var member : task.task.getElements().getAllMembers(classElement)) {
                if (!member.getSimpleName().toString().equals(memberName)) {
                    continue;
                }
                if (member.getKind() == javax.lang.model.element.ElementKind.METHOD) {
                    targetElement = member;
                    break;
                }
                if (fallbackElement == null
                        && (member.getKind() == javax.lang.model.element.ElementKind.FIELD
                                || member.getKind() == javax.lang.model.element.ElementKind.RECORD_COMPONENT)) {
                    fallbackElement = member;
                }
            }
        }

        if (targetElement == null) {
            targetElement = fallbackElement;
        }

        if (targetElement != null) {
            logFine(
                    requestId,
                    "resolveMemberTargetSelected",
                    elapsedMs(memberLookupStarted),
                    targetElement.toString());
        } else {
            logFine(
                    requestId,
                    "resolveMemberTargetMiss",
                    elapsedMs(memberLookupStarted),
                    "className=" + className + ", memberName=" + memberName);
            return List.of();
        }

        // Search for references to this member
        var paths = new ArrayList<TreePath>();
        var scanStarted = System.nanoTime();
        for (var root : task.roots) {
            new FindReferences(task.task, targetElement).scan(root, paths);
        }
        logFine(
                requestId,
                "scanMemberReferencePaths",
                elapsedMs(scanStarted),
                "roots=" + task.roots.size() + ", paths=" + paths.size());

        var locations = new ArrayList<Location>();
        var locationsStarted = System.nanoTime();
        for (var p : paths) {
            locations.add(FindHelper.location(task, p));
        }
        logFine(
                requestId,
                "convertMemberReferenceLocations",
                elapsedMs(locationsStarted),
                "locations=" + locations.size());
        return locations;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private static void logFine(long requestId, String phase, String details) {
        if (!LOG.isLoggable(Level.FINE)) return;
        LOG.fine("[references] req=" + requestId + " phase=" + phase + " " + details);
    }

    private static void logFine(long requestId, String phase, long elapsedMs, String details) {
        if (!LOG.isLoggable(Level.FINE)) return;
        LOG.fine(
                "[references] req="
                        + requestId
                        + " phase="
                        + phase
                        + " elapsedMs="
                        + elapsedMs
                        + " "
                        + details);
    }
}
