package org.javacs.markup;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.ClientCodeWrapper;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.JCDiagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.javacs.CompileTask;

final class DiagnosticFormatter {
    private static final int MAX_PARAMETERS = 7;
    private static final Pattern QUALIFIED_NAME = Pattern.compile("\\b([a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+)\\.([A-Z][\\w]*)");
    private static final Pattern CANT_RESOLVE = Pattern.compile("(?s)cannot find symbol\\s+symbol:\\s+(?:variable|method|class)\\s+(\\S+).*");

    private record Candidate(ExecutableElement method, List<? extends TypeMirror> parameters, JCDiagnostic reason) {}
    private record TypeMismatch(TypeMirror found, TypeMirror required) {}

    private DiagnosticFormatter() {}

    static String format(Diagnostic<? extends JavaFileObject> diagnostic, CompileTask task,
            CompilationUnitTree root) {
        var javac = unwrap(diagnostic);
        var formatted = javac == null ? null : switch (diagnostic.getCode()) {
            case "compiler.err.cant.apply.symbols", "compiler.err.cant.apply.symbol" ->
                    formatArgumentMismatch(javac, task, root);
            case "compiler.err.prob.found.req" -> formatIncompatibleTypes(javac);
            case "compiler.err.ref.ambiguous" -> formatAmbiguousCall(javac);
            case "compiler.err.does.not.override.abstract" -> formatMissingImplementation(javac);
            default -> null;
        };
        if (formatted != null) return formatted;
        return simplifyMessage(diagnostic.getMessage(null));
    }

    private static String formatIncompatibleTypes(JCDiagnostic diagnostic) {
        var args = diagnostic.getArgs();
        if (args.length != 1 || !(args[0] instanceof JCDiagnostic reason)) return null;
        var mismatch = typeMismatch(reason);
        if (mismatch != null) {
            var message = formatRequiredProvided(mismatch);
            if (reason.getCode().equals("compiler.misc.possible.loss.of.precision")) {
                message += "\n\nConversion may lose precision.";
            }
            return message;
        }
        return switch (reason.getCode()) {
            case "compiler.misc.incompatible.ret.type.in.mref", "compiler.misc.incompatible.ret.type.in.lambda" ->
                    formatReturnTypeMismatch(reason);
            case "compiler.misc.incompatible.bounds" -> formatInferenceBounds(reason);
            case "compiler.misc.not.a.functional.intf", "compiler.misc.not.a.functional.intf.1" ->
                    formatFunctionalInterface(reason);
            default -> null;
        };
    }

    private static String formatRequiredProvided(TypeMismatch mismatch) {
        return "Required: " + distinctTypeName(mismatch.required(), mismatch.found())
                + "\nProvided: " + distinctTypeName(mismatch.found(), mismatch.required());
    }

    private static String formatReturnTypeMismatch(JCDiagnostic reason) {
        var args = reason.getArgs();
        if (args.length != 1 || !(args[0] instanceof JCDiagnostic conversion)) return null;
        var mismatch = typeMismatch(conversion);
        if (mismatch == null) return null;
        var context = reason.getCode().equals("compiler.misc.incompatible.ret.type.in.mref")
                ? "Method reference" : "Lambda";
        var message = formatRequiredProvided(mismatch) + "\n\n" + context + " has an incompatible return type.";
        if (conversion.getCode().equals("compiler.misc.possible.loss.of.precision")) {
            message += "\nConversion may lose precision.";
        }
        return message;
    }

    private static String formatInferenceBounds(JCDiagnostic reason) {
        var args = reason.getArgs();
        if (args.length != 3 || !(args[1] instanceof JCDiagnostic first)
                || !(args[2] instanceof JCDiagnostic second)) return null;
        var required = singleBound(first, "compiler.misc.eq.bounds");
        var provided = singleBound(second, "compiler.misc.lower.bounds");
        if (required == null || provided == null) {
            required = singleBound(second, "compiler.misc.eq.bounds");
            provided = singleBound(first, "compiler.misc.lower.bounds");
        }
        if (required == null || provided == null) return null;
        return "Incompatible type argument " + args[0] + "\n" + formatRequiredProvided(new TypeMismatch(provided, required))
                + "\n\n" + simplifyMessage(reason.getMessage(java.util.Locale.ROOT));
    }

    private static TypeMirror singleBound(JCDiagnostic diagnostic, String code) {
        var args = diagnostic.getArgs();
        if (!diagnostic.getCode().equals(code) || args.length != 1 || !(args[0] instanceof Iterable<?> bounds)) return null;
        var iterator = bounds.iterator();
        if (!iterator.hasNext() || !(iterator.next() instanceof TypeMirror type) || iterator.hasNext()) return null;
        return type;
    }

    private static String formatFunctionalInterface(JCDiagnostic reason) {
        var args = reason.getArgs();
        if (args.length == 0 || !(args[0] instanceof TypeElement type)) return null;
        var name = type.getSimpleName().toString();
        String explanation;
        if (args.length == 2 && args[1] instanceof JCDiagnostic detail) {
            explanation = switch (detail.getCode()) {
                case "compiler.misc.incompatible.abstracts" -> "It has multiple unrelated abstract methods.";
                case "compiler.misc.no.abstracts" -> "It has no abstract methods.";
                default -> null;
            };
            if (explanation == null) return null;
        } else {
            // Other causes include sealed interfaces and annotation types; retain javac's detail.
            return null;
        }
        return name + " is not a functional interface.\n" + explanation
                + "\nA lambda or method reference requires a functional interface with one abstract method.";
    }

    private static String formatAmbiguousCall(JCDiagnostic diagnostic) {
        var args = diagnostic.getArgs();
        // JDK 25 Resolve.AmbiguityError: name, first kind/symbol/location, second kind/symbol/location.
        if (args.length != 7 || !(args[2] instanceof ExecutableElement first)
                || !(args[5] instanceof ExecutableElement second)) return null;
        return "Ambiguous call to '" + args[0] + "': multiple candidates match.\n\nCandidates:\n  "
                + formatMethod(first, ((ExecutableType) first.asType()).getParameterTypes(),
                        first.getKind() == ElementKind.CONSTRUCTOR)
                + " in " + first.getEnclosingElement().getSimpleName() + "\n  "
                + formatMethod(second, ((ExecutableType) second.asType()).getParameterTypes(),
                        second.getKind() == ElementKind.CONSTRUCTOR)
                + " in " + second.getEnclosingElement().getSimpleName();
    }

    private static String formatMissingImplementation(JCDiagnostic diagnostic) {
        var args = diagnostic.getArgs();
        // JDK 25 Check.checkAllDefined: class, missing method adapted to the class, declaration owner.
        if (args.length != 3 || !(args[0] instanceof TypeElement type)
                || !(args[1] instanceof ExecutableElement method) || !(args[2] instanceof Element owner)) return null;
        var name = type.getSimpleName().isEmpty() ? "Anonymous class" : "Class '" + type.getSimpleName() + "'";
        return name + " must implement an abstract method from " + owner.getSimpleName() + ":\n  "
                + formatMethod(method, ((ExecutableType) method.asType()).getParameterTypes(), false);
    }

    private static JCDiagnostic unwrap(Diagnostic<? extends JavaFileObject> diagnostic) {
        if (diagnostic instanceof JCDiagnostic javac) return javac;
        if (diagnostic instanceof ClientCodeWrapper.DiagnosticSourceUnwrapper wrapper) return wrapper.d;
        return null;
    }

    private static String formatArgumentMismatch(JCDiagnostic diagnostic, CompileTask task,
            com.sun.source.tree.CompilationUnitTree root) {
        if (diagnostic == null) return null;
        var call = invocationPath(diagnostic, root);
        if (call == null) return null;
        var arguments = arguments(call.getLeaf());
        var candidates = candidates(diagnostic, task, call.getLeaf() instanceof NewClassTree);
        if (candidates.isEmpty()) return null;
        // Never replace a richer inference/accessibility error with a generic mismatch message.
        if (candidates.stream().anyMatch(c -> !isArityMismatch(c.reason()) && typeMismatch(c.reason()) == null)) return null;

        boolean constructor = call.getLeaf() instanceof NewClassTree;
        var message = new StringBuilder("No matching ")
                .append(constructor ? "constructor: " : "method: ")
                .append(callLabel(call.getLeaf(), arguments));
        if (candidates.stream().allMatch(c -> isArityMismatch(c.reason()))) {
            message.append("\n\n").append(formatArgumentCounts(arguments.size(), candidates, constructor));
        }
        message.append("\n\nCandidates:");
        for (var label : candidates.stream().map(c -> formatCandidate(c, constructor)).distinct().toList()) {
            message.append("\n  ").append(label);
        }
        for (var candidate : candidates) {
            if (isArityMismatch(candidate.reason())) continue;
            var mismatch = formatTypeMismatch(candidate, arguments, diagnostic, task, root);
            if (mismatch == null) return null;
            message.append("\n\n");
            if (candidates.size() > 1) message.append("For ").append(formatCandidate(candidate, constructor)).append(":\n  ");
            message.append(mismatch);
        }
        return message.toString();
    }

    private static TreePath invocationPath(JCDiagnostic diagnostic, CompilationUnitTree root) {
        var position = diagnostic.getDiagnosticPosition();
        if (position == null || position.getTree() == null) return null;
        var path = TreePath.getPath(root, position.getTree());
        while (path != null) {
            if (path.getLeaf() instanceof NewClassTree || path.getLeaf() instanceof MethodInvocationTree) return path;
            path = path.getParentPath();
        }
        return null;
    }

    private static List<? extends ExpressionTree> arguments(Tree call) {
        return call instanceof NewClassTree constructor ? constructor.getArguments()
                : ((MethodInvocationTree) call).getArguments();
    }

    private static String callLabel(Tree call, List<? extends ExpressionTree> arguments) {
        String name;
        if (call instanceof NewClassTree constructor) name = constructor.getIdentifier().toString();
        else {
            var select = ((MethodInvocationTree) call).getMethodSelect();
            name = select instanceof MemberSelectTree member ? member.getIdentifier().toString() : select.toString();
        }
        var labels = new StringJoiner(", ", name + "(", ")");
        for (var argument : arguments) labels.add(argument.toString().replaceAll("\\s+", " "));
        return labels.toString();
    }

    private static List<Candidate> candidates(JCDiagnostic diagnostic, CompileTask task, boolean constructor) {
        if (diagnostic.getCode().equals("compiler.err.cant.apply.symbol")) {
            return singleCandidate(diagnostic, task, constructor);
        }
        var result = new ArrayList<Candidate>();
        for (var detail : diagnostic.getSubdiagnostics()) {
            var args = detail.getArgs();
            // JDK 25 Resolve.candidateDetails: kind, location, adapted method symbol, rejection reason.
            if (!detail.getCode().equals("compiler.misc.inapplicable.method") || args.length != 4
                    || !(args[2] instanceof ExecutableElement method)
                    || !(args[3] instanceof JCDiagnostic reason)) return List.of();
            result.add(new Candidate(method, ((ExecutableType) method.asType()).getParameterTypes(), reason));
        }
        return result;
    }

    private static List<Candidate> singleCandidate(JCDiagnostic diagnostic, CompileTask task, boolean constructor) {
        var args = diagnostic.getArgs();
        // JDK 25 Resolve.InapplicableSymbolError: kind, name, required, found, owner kind, owner type, reason.
        if (args.length != 7 || !(args[5] instanceof DeclaredType owner)
                || !(args[6] instanceof JCDiagnostic reason)) return List.of();
        var required = new ArrayList<TypeMirror>();
        if (args[2] instanceof Iterable<?> types) {
            for (var type : types) {
                if (!(type instanceof TypeMirror mirror)) return List.of();
                required.add(mirror);
            }
        }
        var type = (TypeElement) owner.asElement();
        var methods = constructor ? ElementFilter.constructorsIn(type.getEnclosedElements())
                : ElementFilter.methodsIn(task.elements.getAllMembers(type));
        for (var method : methods) {
            if (!constructor && !method.getSimpleName().contentEquals(args[1].toString())) continue;
            var parameters = ((ExecutableType) task.types.asMemberOf(owner, method)).getParameterTypes();
            if (parameters.size() != required.size()) continue;
            boolean same = true;
            for (int i = 0; i < parameters.size(); i++) {
                if (!task.types.isSameType(parameters.get(i), required.get(i))) same = false;
            }
            if (same) return List.of(new Candidate(method, parameters, reason));
        }
        return List.of();
    }

    private static boolean isArityMismatch(JCDiagnostic reason) {
        return reason.getCode().equals("compiler.misc.arg.length.mismatch")
                || reason.getCode().equals("compiler.misc.infer.arg.length.mismatch");
    }

    private static String formatArgumentCounts(int passed, List<Candidate> candidates, boolean constructor) {
        var counts = new TreeSet<Integer>();
        var variable = new TreeSet<Integer>();
        for (var candidate : candidates) {
            int count = candidate.parameters().size();
            if (candidate.method().isVarArgs()) variable.add(count - 1);
            else counts.add(count);
        }
        var labels = new ArrayList<String>();
        counts.forEach(count -> labels.add(count.toString()));
        variable.forEach(count -> labels.add("at least " + count));
        var accepted = labels.size() < 2 ? labels.getFirst()
                : String.join(", ", labels.subList(0, labels.size() - 1)) + " or " + labels.getLast();
        return "Passed " + passed + (passed == 1 ? " argument" : " arguments")
                + "; available " + (constructor ? "constructors" : "methods") + " accept " + accepted + ".";
    }

    private static String formatCandidate(Candidate candidate, boolean constructor) {
        return formatMethod(candidate.method(), candidate.parameters(), constructor);
    }

    private static String formatMethod(ExecutableElement method, List<? extends TypeMirror> types, boolean constructor) {
        var name = constructor ? method.getEnclosingElement().getSimpleName() : method.getSimpleName();
        var parameters = new StringJoiner(", ", name + "(", ")");
        for (int i = 0; i < Math.min(types.size(), MAX_PARAMETERS); i++) {
            var type = types.get(i);
            var label = method.isVarArgs() && i == types.size() - 1 && type instanceof ArrayType array
                    ? simpleType(array.getComponentType()) + "..." : simpleType(type);
            var parameterName = method.getParameters().get(i).getSimpleName().toString();
            if (!parameterName.matches("arg\\d+")) label += " " + parameterName;
            parameters.add(label);
        }
        if (types.size() > MAX_PARAMETERS) parameters.add("…");
        return parameters.toString();
    }

    private static TypeMismatch typeMismatch(JCDiagnostic reason) {
        var args = reason.getArgs();
        if ((reason.getCode().equals("compiler.misc.inconvertible.types")
                || reason.getCode().equals("compiler.misc.possible.loss.of.precision"))
                && args.length == 2 && args[0] instanceof TypeMirror found && args[1] instanceof TypeMirror required) {
            return new TypeMismatch(found, required);
        }
        // Only unwrap ordinary argument conversions, never generic inference failures.
        if ((reason.getCode().equals("compiler.misc.no.conforming.assignment.exists")
                || reason.getCode().equals("compiler.misc.varargs.argument.mismatch"))
                && args.length == 1 && args[0] instanceof JCDiagnostic nested) return typeMismatch(nested);
        return null;
    }

    private static String formatTypeMismatch(Candidate candidate, List<? extends ExpressionTree> arguments,
            JCDiagnostic diagnostic, CompileTask task, CompilationUnitTree root) {
        var mismatch = typeMismatch(candidate.reason());
        var position = candidate.reason().getDiagnosticPosition();
        long offset = position == null ? diagnostic.getPosition() : position.getPreferredPosition();
        var positions = task.trees.getSourcePositions();
        for (int i = 0; i < arguments.size(); i++) {
            if (positions.getStartPosition(root, arguments.get(i)) > offset
                    || positions.getEndPosition(root, arguments.get(i)) <= offset) continue;
            int parameter = Math.min(i, candidate.method().getParameters().size() - 1);
            if (parameter < 0) return null;
            var name = candidate.method().getParameters().get(parameter).getSimpleName().toString();
            var label = name.matches("arg\\d+") ? "" : " '" + name + "'";
            return "Argument " + (i + 1) + label + ": expected " + distinctTypeName(mismatch.required(), mismatch.found())
                    + ", received " + distinctTypeName(mismatch.found(), mismatch.required()) + ".";
        }
        return null;
    }

    private static String simpleType(TypeMirror type) {
        if (type instanceof Type.CapturedType captured) return "capture of " + simpleType(captured.wildcard);
        return type.getKind() == TypeKind.NULL ? "null" : QUALIFIED_NAME.matcher(type.toString()).replaceAll("$3");
    }

    private static String distinctTypeName(TypeMirror type, TypeMirror other) {
        var label = simpleType(type);
        // Keep package names or capture identities when shortening would erase the distinction.
        if (label.equals(simpleType(other)) && !type.toString().equals(other.toString())) return type.toString();
        return label;
    }

    private static String simplifyMessage(String message) {
        var missing = CANT_RESOLVE.matcher(message);
        if (missing.matches()) return "cannot resolve symbol '" + missing.group(1) + "'";
        return QUALIFIED_NAME.matcher(message).replaceAll("$3");
    }
}
