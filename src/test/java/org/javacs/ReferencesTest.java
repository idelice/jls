package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.*;
import org.junit.AfterClass;
import org.junit.Ignore;
import org.junit.Test;

public class ReferencesTest {
    @Test
    public void privateFieldReferences() {
        assertThat(
                references("/org/javacs/example/ReferencesPrivate.java", 13, 9),
                containsInAnyOrder("ReferencesPrivate.java:8", "ReferencesPrivate.java:13"));
    }

    @Test
    public void privateMethodReferences() {
        assertThat(
                references("/org/javacs/example/ReferencesPrivate.java", 12, 9),
                containsInAnyOrder("ReferencesPrivate.java:7", "ReferencesPrivate.java:12"));
    }

    @Test
    public void packageLocalFieldReferences() {
        assertThat(
                references("/org/javacs/example/PackageLocalTarget.java", 7, 9),
                containsInAnyOrder("PackageLocalTarget.java:7", "PackageLocalUse.java:5"));
    }

    @Test
    public void packageLocalMethodReferences() {
        assertThat(
                references("/org/javacs/example/PackageLocalTarget.java", 6, 10),
                containsInAnyOrder("PackageLocalTarget.java:6", "PackageLocalUse.java:6"));
    }

    @Test
    public void packageLocalClassReferences() {
        assertThat(
                references("/org/javacs/example/PackageLocalUse.java", 4, 25),
                containsInAnyOrder("PackageLocalUse.java:4", "PackageLocalUse.java:9"));
    }

    @Test
    public void localMultiDeclarator() {
        assertThat(
                references("/org/javacs/example/ReferencesLocals.java", 12, 13),
                containsInAnyOrder("ReferencesLocals.java:12", "ReferencesLocals.java:14"));
    }

    @Test
    public void localInForInit() {
        // Two uses of i on the for line: the condition and the update.
        assertThat(
                references("/org/javacs/example/ReferencesLocals.java", 19, 18),
                containsInAnyOrder(
                        "ReferencesLocals.java:19", "ReferencesLocals.java:19", "ReferencesLocals.java:20"));
    }

    @Test
    public void localInForEach() {
        assertThat(
                references("/org/javacs/example/ReferencesLocals.java", 26, 21),
                containsInAnyOrder("ReferencesLocals.java:27", "ReferencesLocals.java:28"));
    }

    @Test
    public void localInTryWithResources() {
        assertThat(
                references("/org/javacs/example/ReferencesLocals.java", 33, 18),
                containsInAnyOrder("ReferencesLocals.java:34", "ReferencesLocals.java:35"));
    }

    @Test
    public void catchParameter() {
        // Line 46 belongs to the same-named parameter of the second catch clause.
        assertThat(
                references("/org/javacs/example/ReferencesLocals.java", 42, 30),
                containsInAnyOrder("ReferencesLocals.java:43", "ReferencesLocals.java:44"));
    }

    @Test
    public void multiCatchParameter() {
        assertThat(
                references("/org/javacs/example/ReferencesLocals.java", 53, 45),
                containsInAnyOrder("ReferencesLocals.java:54", "ReferencesLocals.java:55"));
    }

    @Test
    public void methodParameter() {
        // Line 12 is the same-named parameter of a different method.
        assertThat(
                references("/org/javacs/example/ReferencesParameters.java", 7, 25),
                containsInAnyOrder("ReferencesParameters.java:8"));
    }

    @Test
    public void constructorParameterVsField() {
        // Only the RHS of `this.x = x;`; not `this.x` and not the field reads on lines 19 and 27.
        assertThat(
                references("/org/javacs/example/ReferencesParameters.java", 22, 18),
                containsInAnyOrder("ReferencesParameters.java:23"));
    }

    @Test
    public void varargsParameter() {
        assertThat(
                references("/org/javacs/example/ReferencesParameters.java", 31, 28),
                containsInAnyOrder("ReferencesParameters.java:32", "ReferencesParameters.java:33"));
    }

    @Test
    public void lambdaExplicitParameter() {
        assertThat(
                references("/org/javacs/example/ReferencesParameters.java", 37, 46),
                containsInAnyOrder("ReferencesParameters.java:38", "ReferencesParameters.java:39"));
    }

    @Test
    public void lambdaImplicitParameter() {
        // The second lambda on line 46 has its own parameter named x; it must not appear.
        assertThat(
                references("/org/javacs/example/ReferencesParameters.java", 45, 36),
                containsInAnyOrder("ReferencesParameters.java:45"));
    }

    @Test
    public void capturedLocalInAnonymousClass() {
        // Cursor is on the captured use inside the anonymous class.
        assertThat(
                references("/org/javacs/example/ReferencesLocals.java", 64, 21),
                containsInAnyOrder("ReferencesLocals.java:61", "ReferencesLocals.java:64"));
    }

    @Test
    public void patternVariableInstanceof() {
        assertThat(
                references("/org/javacs/example/ReferencesLocals.java", 71, 33),
                containsInAnyOrder("ReferencesLocals.java:72", "ReferencesLocals.java:73"));
    }

    @Test
    public void patternVariableAfterEarlyExit() {
        assertThat(
                references("/org/javacs/example/ReferencesLocals.java", 79, 35),
                containsInAnyOrder("ReferencesLocals.java:82", "ReferencesLocals.java:83"));
    }

    @Test
    public void varLocal() {
        // `var` is not a type reference, so only the uses of `list` are returned.
        assertThat(
                references("/org/javacs/example/ReferencesLocals.java", 87, 13),
                containsInAnyOrder(
                        "ReferencesLocals.java:88", "ReferencesLocals.java:89", "ReferencesLocals.java:90"));
    }

    @Test
    public void privateStaticField() {
        // The same-named private field of ReferencesPrivateStaticFieldOther must not appear.
        assertThat(
                references("/org/javacs/example/ReferencesPrivateStaticField.java", 4, 24),
                containsInAnyOrder(
                        "ReferencesPrivateStaticField.java:7",
                        "ReferencesPrivateStaticField.java:11",
                        "ReferencesPrivateStaticField.java:15"));
    }

    @Test
    public void packagePrivateStaticField() {
        assertThat(
                references("/org/javacs/example/ReferencesPackageStaticField.java", 4, 16),
                containsInAnyOrder(
                        "ReferencesPackageStaticField.java:7", "ReferencesPackageStaticField.java:13"));
    }

    @Test
    public void protectedInstanceField() {
        assertThat(
                references("/org/javacs/example/ReferencesProtectedBase.java", 4, 19),
                containsInAnyOrder(
                        "ReferencesProtectedBase.java:9",
                        "ReferencesProtectedSubclass.java:7",
                        "ReferencesProtectedSubclass.java:11"));
    }

    @Test
    public void protectedStaticField() {
        assertThat(
                references("/org/javacs/example/ReferencesProtectedStaticBase.java", 4, 26),
                containsInAnyOrder(
                        "ReferencesProtectedStaticBase.java:9", "ReferencesProtectedStaticSubclass.java:7"));
    }

    @Test
    public void publicInstanceField() {
        assertThat(
                references("/org/javacs/example/ReferencesPublicBase.java", 4, 16),
                containsInAnyOrder("ReferencesPublicInstanceUse.java:7"));
    }

    @Test
    public void publicStaticField() {
        assertThat(
                references("/org/javacs/example/ReferencesPublicStaticBase.java", 4, 23),
                containsInAnyOrder("ReferencesPublicStaticUse.java:7", "ReferencesPublicStaticUse.java:11"));
    }

    @Test
    public void fieldMultiDeclarator() {
        // `b` is a separate field and must not appear.
        assertThat(
                references("/org/javacs/example/ReferencesFieldMultiDeclarator.java", 4, 17),
                containsInAnyOrder(
                        "ReferencesFieldMultiDeclarator.java:4", "ReferencesFieldMultiDeclarator.java:7"));
    }

    @Test
    public void fieldInInitializerAndConstructor() {
        assertThat(
                references("/org/javacs/example/ReferencesFieldInitializer.java", 4, 17),
                containsInAnyOrder(
                        "ReferencesFieldInitializer.java:5",
                        "ReferencesFieldInitializer.java:8",
                        "ReferencesFieldInitializer.java:12",
                        "ReferencesFieldInitializer.java:16"));
    }

    @Test
    public void fieldIncrementAndCompoundAssign() {
        assertThat(
                references("/org/javacs/example/ReferencesFieldIncrement.java", 4, 17),
                containsInAnyOrder(
                        "ReferencesFieldIncrement.java:7",
                        "ReferencesFieldIncrement.java:8",
                        "ReferencesFieldIncrement.java:9",
                        "ReferencesFieldIncrement.java:10"));
    }

    @Test
    public void blankFinalField() {
        // The declaration is not returned: the constructor assignment and the method read are.
        assertThat(
                references("/org/javacs/example/ReferencesBlankFinal.java", 4, 23),
                containsInAnyOrder("ReferencesBlankFinal.java:7", "ReferencesBlankFinal.java:11"));
    }

    @Test
    public void fieldInLambda() {
        // `this` inside the lambda is the enclosing instance, so both `this.f` and bare `f` match.
        assertThat(
                references("/org/javacs/example/ReferencesFieldLambda.java", 4, 17),
                containsInAnyOrder(
                        "ReferencesFieldLambda.java:8",
                        "ReferencesFieldLambda.java:9",
                        "ReferencesFieldLambda.java:9"));
    }

    @Test
    public void outerThisQualifiedField() {
        // Only Outer.this.f; the bare inner read on line 10 must not be conflated with it.
        assertThat(
                references("/org/javacs/example/ReferencesOuterThis.java", 4, 17),
                containsInAnyOrder("ReferencesOuterThis.java:14"));
    }

    @Test
    public void interfaceConstant() {
        assertThat(
                references("/org/javacs/example/ReferencesInterfaceConstant.java", 4, 9),
                containsInAnyOrder(
                        "ReferencesInterfaceConstant.java:9", "ReferencesInterfaceConstant.java:15"));
    }

    @Test
    public void privateInstanceMethodWithReferences() {
        assertThat(
                references("/org/javacs/example/ReferencesPrivateInstanceMethod.java", 4, 18),
                containsInAnyOrder(
                        "ReferencesPrivateInstanceMethod.java:4",
                        "ReferencesPrivateInstanceMethod.java:7",
                        "ReferencesPrivateInstanceMethod.java:11",
                        "ReferencesPrivateInstanceMethod.java:15"));
    }

    @Test
    public void protectedInstanceMethod() {
        // A base-typed receiver is illegal for protected access outside the package, so the
        // subclass uses an inherited unqualified call, `this.m()`, and a subclass-typed receiver.
        assertThat(
                references("/org/javacs/example/ReferencesProtectedMethodBase.java", 4, 20),
                containsInAnyOrder(
                        "ReferencesProtectedMethodBase.java:4",
                        "ReferencesProtectedMethodBase.java:7",
                        "ReferencesProtectedMethodSubclass.java:7",
                        "ReferencesProtectedMethodSubclass.java:11",
                        "ReferencesProtectedMethodSubclass.java:15"));
    }

    @Test
    public void publicInstanceMethod() {
        assertThat(
                references("/org/javacs/example/ReferencesPublicMethodBase.java", 4, 17),
                containsInAnyOrder(
                        "ReferencesPublicMethodBase.java:4", "ReferencesPublicMethodUse.java:7"));
    }

    @Test
    public void privateStaticMethod() {
        assertThat(
                references("/org/javacs/example/ReferencesPrivateStaticMethod.java", 4, 25),
                containsInAnyOrder(
                        "ReferencesPrivateStaticMethod.java:4",
                        "ReferencesPrivateStaticMethod.java:7",
                        "ReferencesPrivateStaticMethod.java:11",
                        "ReferencesPrivateStaticMethod.java:15",
                        "ReferencesPrivateStaticMethod.java:19"));
    }

    @Test
    public void packagePrivateStaticMethod() {
        assertThat(
                references("/org/javacs/example/ReferencesPackageStaticMethod.java", 4, 17),
                containsInAnyOrder(
                        "ReferencesPackageStaticMethod.java:4",
                        "ReferencesPackageStaticMethod.java:7",
                        "ReferencesPackageStaticMethodUse.java:5"));
    }

    @Test
    public void protectedStaticMethod() {
        assertThat(
                references("/org/javacs/example/ReferencesProtectedStaticMethodBase.java", 4, 27),
                containsInAnyOrder(
                        "ReferencesProtectedStaticMethodBase.java:4",
                        "ReferencesProtectedStaticMethodBase.java:7",
                        "ReferencesProtectedStaticMethodSubclass.java:7"));
    }

    @Test
    public void publicStaticMethod() {
        assertThat(
                references("/org/javacs/example/ReferencesPublicStaticMethodBase.java", 4, 24),
                containsInAnyOrder(
                        "ReferencesPublicStaticMethodBase.java:4", "ReferencesPublicStaticMethodUse.java:7"));
    }

    @Test
    public void recursiveMethod() {
        assertThat(
                references("/org/javacs/example/ReferencesRecursiveMethod.java", 4, 10),
                containsInAnyOrder(
                        "ReferencesRecursiveMethod.java:4", "ReferencesRecursiveMethod.java:6"));
    }

    @Test
    public void overloadDifferentArity() {
        assertThat(
                references("/org/javacs/example/ReferencesOverloadArity.java", 4, 10),
                containsInAnyOrder(
                        "ReferencesOverloadArity.java:4", "ReferencesOverloadArity.java:9"));
        // The two-argument overload and its call must not leak into the one-argument scan.
        assertThat(
                references("/org/javacs/example/ReferencesOverloadArity.java", 6, 10),
                containsInAnyOrder(
                        "ReferencesOverloadArity.java:6", "ReferencesOverloadArity.java:13"));
    }

    @Test
    public void overloadSameArityDifferentTypes() {
        assertThat(
                references("/org/javacs/example/ReferencesOverloadTypes.java", 4, 10),
                containsInAnyOrder(
                        "ReferencesOverloadTypes.java:4", "ReferencesOverloadTypes.java:9"));
        assertThat(
                references("/org/javacs/example/ReferencesOverloadTypes.java", 6, 10),
                containsInAnyOrder(
                        "ReferencesOverloadTypes.java:6", "ReferencesOverloadTypes.java:13"));
    }

    @Test
    public void overloadWideningBeforeBoxing() {
        // m(1) widens to long rather than boxing to Integer; the explicit valueOf call selects
        // the Integer overload.
        assertThat(
                references("/org/javacs/example/ReferencesOverloadWidening.java", 4, 10),
                containsInAnyOrder(
                        "ReferencesOverloadWidening.java:4", "ReferencesOverloadWidening.java:9"));
        assertThat(
                references("/org/javacs/example/ReferencesOverloadWidening.java", 6, 10),
                containsInAnyOrder(
                        "ReferencesOverloadWidening.java:6", "ReferencesOverloadWidening.java:13"));
    }

    @Test
    public void overloadVarargsVsFixedArity() {
        // m(1, 2) uses the fixed-arity overload; m() and m(5) use the varargs overload.
        assertThat(
                references("/org/javacs/example/ReferencesOverloadVarargs.java", 4, 10),
                containsInAnyOrder(
                        "ReferencesOverloadVarargs.java:4",
                        "ReferencesOverloadVarargs.java:13",
                        "ReferencesOverloadVarargs.java:17"));
        assertThat(
                references("/org/javacs/example/ReferencesOverloadVarargs.java", 6, 10),
                containsInAnyOrder(
                        "ReferencesOverloadVarargs.java:6", "ReferencesOverloadVarargs.java:9"));
    }

    @Test
    public void genericMethodInvocation() {
        assertThat(
                references("/org/javacs/example/ReferencesGenericMethod.java", 4, 11),
                containsInAnyOrder(
                        "ReferencesGenericMethod.java:4",
                        "ReferencesGenericMethod.java:9",
                        "ReferencesGenericMethod.java:10"));
    }

    @Test
    public void methodCalledFromNestedClass() {
        assertThat(
                references("/org/javacs/example/ReferencesNestedCall.java", 4, 18),
                containsInAnyOrder(
                        "ReferencesNestedCall.java:4",
                        "ReferencesNestedCall.java:8",
                        "ReferencesNestedCall.java:14"));
    }

    @Test
    public void staticMethodViaInstance() {
        assertThat(
                references("/org/javacs/example/ReferencesStaticViaInstance.java", 4, 17),
                containsInAnyOrder(
                        "ReferencesStaticViaInstance.java:4", "ReferencesStaticViaInstance.java:7"));
    }

    @Test
    public void patternVariableInSwitch() {
        // Cursor is on the binding variable in `case String s`; locals do not include their
        // declaration, so only the uses inside the switch arm are expected.
        assertThat(
                references("/org/javacs/example/ReferencesSwitchPattern.java", 8, 25),
                containsInAnyOrder(
                        "ReferencesSwitchPattern.java:9", "ReferencesSwitchPattern.java:10"));
    }

    @Test
    public void explicitNoArgConstructor() {
        // The constructor declaration is a method, so it is included alongside the creation and
        // the constructor reference.
        assertThat(
                references("/org/javacs/example/ConstructorNoArg.java", 6, 17),
                containsInAnyOrder(
                        "ConstructorNoArg.java:6",
                        "ConstructorNoArg.java:9",
                        "ConstructorNoArg.java:10"));
    }

    @Test
    public void parameterizedConstructor() {
        // The no-arg constructor declaration on line 4 and the no-arg creation on line 9 must not
        // leak into the scan for the int constructor.
        assertThat(
                references("/org/javacs/example/ConstructorParameterized.java", 6, 16),
                containsInAnyOrder("ConstructorParameterized.java:6", "ConstructorParameterized.java:10"));
    }

    @Test
    public void overloadedConstructorsSameArity() {
        assertThat(
                references("/org/javacs/example/ConstructorOverloads.java", 4, 16),
                containsInAnyOrder("ConstructorOverloads.java:4", "ConstructorOverloads.java:9"));
        assertThat(
                references("/org/javacs/example/ConstructorOverloads.java", 6, 16),
                containsInAnyOrder("ConstructorOverloads.java:6", "ConstructorOverloads.java:10"));
    }

    @Test
    public void thisConstructorDelegation() {
        assertThat(
                references("/org/javacs/example/ConstructorThisDelegation.java", 4, 16),
                containsInAnyOrder(
                        "ConstructorThisDelegation.java:4",
                        "ConstructorThisDelegation.java:7"));
    }

    @Test
    public void superConstructorInvocation() {
        assertThat(
                references("/org/javacs/example/SuperConstructorBase.java", 4, 19),
                containsInAnyOrder("SuperConstructorBase.java:4", "SuperConstructorSubclass.java:7"));
    }

    @Test
    public void implicitDefaultConstructor() {
        // There is no source declaration for the implicit default constructor, so the cursor is on
        // a creation and both creation sites are expected.
        assertThat(
                references("/org/javacs/example/ImplicitDefaultConstructor.java", 5, 9),
                containsInAnyOrder("ImplicitDefaultConstructor.java:5", "ImplicitDefaultConstructor.java:6"));
    }

    @Test
    public void recordCanonicalConstructor() {
        // The canonical constructor has no source declaration; only creation sites can appear.
        assertThat(
                references("/org/javacs/example/RecordCanonicalConstructor.java", 5, 16),
                containsInAnyOrder(
                        "RecordCanonicalConstructor.java:5", "RecordCanonicalConstructor.java:9"));
    }

    @Test
    public void recordCompactConstructor() {
        assertThat(
                references("/org/javacs/example/RecordCompactConstructor.java", 4, 16),
                containsInAnyOrder(
                        "RecordCompactConstructor.java:4",
                        "RecordCompactConstructor.java:8",
                        "RecordCompactConstructor.java:12"));
    }

    @Test
    public void enumConstructorFromConstant() {
        assertThat(
                references("/org/javacs/example/EnumConstructorFromConstant.java", 7, 9),
                containsInAnyOrder(
                        "EnumConstructorFromConstant.java:4",
                        "EnumConstructorFromConstant.java:5",
                        "EnumConstructorFromConstant.java:7"));
    }

    @Test
    public void constructorReferenceTargetTyped() {
        // The target type selects the overload, so each side must resolve to its own constructor.
        assertThat(
                references("/org/javacs/example/ConstructorReferenceTargetTyped.java", 7, 16),
                containsInAnyOrder(
                        "ConstructorReferenceTargetTyped.java:7", "ConstructorReferenceTargetTyped.java:12"));
        assertThat(
                references("/org/javacs/example/ConstructorReferenceTargetTyped.java", 9, 16),
                containsInAnyOrder(
                        "ConstructorReferenceTargetTyped.java:9", "ConstructorReferenceTargetTyped.java:13"));
    }

    @Test
    public void anonymousClassConstructorCall() {
        assertThat(
                references("/org/javacs/example/AnonymousConstructor.java", 4, 16),
                containsInAnyOrder(
                        "AnonymousConstructor.java:4",
                        "AnonymousConstructor.java:7",
                        "AnonymousConstructor.java:8"));
    }

    @Test
    public void jdkMemberDoesNotThrow() {
        // System.out's declaring type is not workspace source, so the declaration path is NOT_FOUND.
        // The request must resolve the local use instead of failing to compile the empty path.
        assertThat(
                references("/org/javacs/example/ReferencesJdkMember.java", 5, 16),
                hasItem("ReferencesJdkMember.java:5"));
    }

    @Test
    public void genericAnonymousConstructor() {
        // new Box<String>("x") {} invokes Box(T) after substituting T = String.
        assertThat(
                references("/org/javacs/example/GenericAnonymousConstructor.java", 5, 10),
                containsInAnyOrder("GenericAnonymousConstructor.java:5", "GenericAnonymousConstructor.java:9"));
    }

    @Test
    public void qualifiedNewInnerClass() {
        assertThat(
                references("/org/javacs/example/QualifiedNewInnerClass.java", 5, 18),
                containsInAnyOrder("QualifiedNewInnerClass.java:5", "QualifiedNewInnerClass.java:9"));
    }

    @Test
    public void classTypeInDeclarations() {
        // Field, local variable, parameter, return type, extends clause, array element type and
        // generic argument are all type references; the declaration itself is not returned.
        assertThat(
                references("/org/javacs/example/TypeRefDeclarations.java", 4, 8),
                containsInAnyOrder(
                        "TypeRefDeclarations.java:3",
                        "TypeRefDeclarations.java:4",
                        "TypeRefDeclarations.java:6",
                        "TypeRefDeclarations.java:6",
                        "TypeRefDeclarations.java:7",
                        "TypeRefDeclarations.java:8",
                        "TypeRefDeclarations.java:9"));
    }

    @Test
    public void classLiteral() {
        assertThat(
                references("/org/javacs/example/TypeRefClassLiteral.java", 4, 28),
                containsInAnyOrder("TypeRefClassLiteral.java:4", "TypeRefClassLiteral.java:5"));
    }

    @Test
    public void instanceofType() {
        assertThat(
                references("/org/javacs/example/TypeRefInstanceof.java", 5, 34),
                containsInAnyOrder("TypeRefInstanceof.java:5"));
    }

    @Test
    public void castType() {
        assertThat(
                references("/org/javacs/example/TypeRefCast.java", 5, 22),
                containsInAnyOrder("TypeRefCast.java:5"));
    }

    @Test
    public void arrayOfReferenceType() {
        // Both the declaration type and the array creation type are references; the array
        // creation is not a constructor reference.
        assertThat(
                references("/org/javacs/example/TypeRefArray.java", 5, 14),
                containsInAnyOrder("TypeRefArray.java:5", "TypeRefArray.java:6"));
    }

    @Test
    public void genericTypeArguments() {
        // Only the TypeRefGenTarget occurrences match; the generic classes are separate types.
        assertThat(
                references("/org/javacs/example/TypeRefGenerics.java", 4, 24),
                containsInAnyOrder("TypeRefGenerics.java:4", "TypeRefGenerics.java:5"));
    }

    @Test
    public void diamondInference() {
        // The <> argument has no symbol, so only the Box type token is a reference.
        assertThat(
                references("/org/javacs/example/TypeRefDiamond.java", 4, 27),
                containsInAnyOrder("TypeRefDiamond.java:4"));
    }

    @Test
    public void interfaceTypeReferences() {
        assertThat(
                references("/org/javacs/example/TypeRefInterfaceUser.java", 4, 9),
                containsInAnyOrder(
                        "TypeRefInterfaceUser.java:3",
                        "TypeRefInterfaceUser.java:4",
                        "TypeRefInterfaceUser.java:7",
                        "TypeRefInterfaceUser.java:8",
                        "TypeRefInterfaceUser.java:9"));
    }

    @Test
    public void enumTypeReferences() {
        // The switch selector is a variable, not a type reference.
        assertThat(
                references("/org/javacs/example/TypeRefEnumUser.java", 4, 9),
                containsInAnyOrder(
                        "TypeRefEnumUser.java:4",
                        "TypeRefEnumUser.java:6",
                        "TypeRefEnumUser.java:7",
                        "TypeRefEnumUser.java:8"));
    }

    @Test
    public void enumConstantReferences() {
        // The enum constant declaration is not returned; only the qualified uses are.
        assertThat(
                references("/org/javacs/example/TypeRefConstUser.java", 4, 50),
                containsInAnyOrder("TypeRefConstUser.java:4", "TypeRefConstUser.java:7"));
    }

    @Test
    public void enumConstantInSwitchCase() {
        assertThat(
                references("/org/javacs/example/TypeRefSwitchCaseUser.java", 6, 19),
                containsInAnyOrder("TypeRefSwitchCaseUser.java:6"));
    }

    @Test
    public void recordTypeReferences() {
        // The creation on line 7 invokes a constructor, but its type token is a type reference.
        assertThat(
                references("/org/javacs/example/TypeRefRecordUser.java", 4, 9),
                containsInAnyOrder(
                        "TypeRefRecordUser.java:4",
                        "TypeRefRecordUser.java:7",
                        "TypeRefRecordUser.java:7",
                        "TypeRefRecordUser.java:8"));
    }

    @Test
    public void recordAccessorMethod() {
        // The explicit accessor declaration is a method, so it is returned.
        assertThat(
                references("/org/javacs/example/TypeRefAccessorRecord.java", 4, 16),
                containsInAnyOrder(
                        "TypeRefAccessorRecord.java:4",
                        "TypeRefAccessorUser.java:5",
                        "TypeRefAccessorUser.java:9"));
    }

    @Test
    public void nestedClassTypeReferences() {
        // Qualified and simple uses of the member class, but not its declaration.
        assertThat(
                references("/org/javacs/example/TypeRefOuter.java", 6, 7),
                containsInAnyOrder(
                        "TypeRefOuter.java:6", "TypeRefOuter.java:9", "TypeRefOuter.java:10"));
    }

    @Test
    public void localClassTypeReferences() {
        // The local class declaration on line 5 is not returned; both uses on line 7 are.
        assertThat(
                references("/org/javacs/example/TypeRefLocalClass.java", 7, 11),
                containsInAnyOrder(
                        "TypeRefLocalClass.java:7", "TypeRefLocalClass.java:7"));
    }

    @Test
    public void anonymousClassTypeReference() {
        // The anonymous class has no name; the supertype token is the type reference.
        assertThat(
                references("/org/javacs/example/TypeRefAnonymous.java", 9, 25),
                containsInAnyOrder("TypeRefAnonymous.java:9"));
    }

    @Test
    public void javaLangTypeReference() {
        // java.lang is implicitly imported, so the file never spells out the package name.
        assertThat(
                references("/org/javacs/example/TypeRefJavaLang.java", 5, 25),
                containsInAnyOrder("TypeRefJavaLang.java:5"));
    }

    @Test
    public void classTypeParameter() {
        // Only the uses of the type parameter are returned; its declaration on line 3 is not.
        assertThat(
                references("/org/javacs/example/TypeParamBox.java", 4, 5),
                containsInAnyOrder(
                        "TypeParamBox.java:4",
                        "TypeParamBox.java:6",
                        "TypeParamBox.java:6",
                        "TypeParamBox.java:7"));
        // A cursor on the type-parameter declaration itself resolves no symbol, so the scan is empty.
        assertThat(references("/org/javacs/example/TypeParamBox.java", 3, 22), empty());
    }

    @Test
    public void methodTypeParameter() {
        // The class type parameter U on lines 4 and 11 and the top-level class U are different symbols.
        assertThat(
                references("/org/javacs/example/TypeParamMethod.java", 6, 9),
                containsInAnyOrder(
                        "TypeParamMethod.java:6", "TypeParamMethod.java:6", "TypeParamMethod.java:7"));
    }

    @Test
    public void boundedTypeParameter() {
        // Number is java.lang and also appears in the wildcard fixture, so both files are expected.
        assertThat(
                references("/org/javacs/example/TypeParamBound.java", 3, 32),
                containsInAnyOrder("TypeParamBound.java:3", "TypeParamWildcard.java:4"));
        // Comparable is java.lang, so the use in the err package is part of the same symbol.
        assertThat(
                references("/org/javacs/example/TypeParamBound.java", 3, 41),
                containsInAnyOrder("MissingOverride.java:5", "TypeParamBound.java:3"));
        // The T in Comparable<T> and the field type on line 4 are uses; the declaration is not.
        assertThat(
                references("/org/javacs/example/TypeParamBound.java", 3, 52),
                containsInAnyOrder("TypeParamBound.java:3", "TypeParamBound.java:4"));
    }

    @Test
    public void wildcardBounds() {
        // java.lang.Number is shared with the bounded type-parameter fixture.
        assertThat(
                references("/org/javacs/example/TypeParamWildcard.java", 4, 37),
                containsInAnyOrder("TypeParamWildcard.java:4", "TypeParamBound.java:3"));
        // Integer is java.lang too, so every use anywhere in the fixture project is the same symbol.
        assertThat(
                references("/org/javacs/example/TypeParamWildcard.java", 5, 35),
                containsInAnyOrder(
                        "TypeParamWildcard.java:5",
                        "ReferencesOverloadWidening.java:6",
                        "ReferencesOverloadWidening.java:13",
                        "ReferencesParameters.java:44",
                        "ReferencesParameters.java:44",
                        "TestAddImport.java:4",
                        "TestAddImportAnonymousClass.java:6",
                        "TestOverrideInheritedMethod.java:5",
                        "TestOverrideInheritedMethod.java:8",
                        "TestOverrideInheritedMethod.java:9",
                        "Unused.java:12"));
        // The wildcard itself has no symbol.
        assertThat(references("/org/javacs/example/TypeParamWildcard.java", 4, 26), empty());
    }

    @Test
    public void intersectionCast() {
        // Each interface in the intersection cast is its own type reference.
        assertThat(
                references("/org/javacs/example/TypeParamCast.java", 5, 18),
                containsInAnyOrder("TypeParamCast.java:5"));
        assertThat(
                references("/org/javacs/example/TypeParamCast.java", 5, 39),
                containsInAnyOrder("TypeParamCast.java:5"));
    }

    @Test
    public void throwsClauseType() {
        // The exception type in the throws clause is a type reference; its declaration is not returned.
        assertThat(
                references("/org/javacs/example/TypeParamThrows.java", 4, 22),
                containsInAnyOrder("TypeParamThrows.java:4"));
    }

    @Test
    public void catchClauseType() {
        // The exception type in the catch clause is a type reference; its declaration is not returned.
        assertThat(
                references("/org/javacs/example/TypeParamCatch.java", 7, 19),
                containsInAnyOrder("TypeParamCatch.java:7"));
        // The catch variable e is a different symbol: its only use is the body read on line 8.
        assertThat(
                references("/org/javacs/example/TypeParamCatch.java", 7, 42),
                containsInAnyOrder("TypeParamCatch.java:8"));
    }

    @Test
    public void annotationTypeReferences() {
        // Every @AnnotationRefType use is a type reference; the annotation declaration is not.
        assertThat(
                references("/org/javacs/example/AnnotationRefUse.java", 3, 2),
                containsInAnyOrder(
                        "AnnotationRefUse.java:3",
                        "AnnotationRefUse.java:5",
                        "AnnotationRefUse.java:8",
                        "AnnotationRefUse.java:11",
                        "AnnotationRefUse.java:12",
                        "AnnotationRefUse.java:13"));
    }

    @Test
    public void annotationElementUse() {
        // @A("x") is shorthand for @A(value = "x"), so both lines use value(); the declaration is a
        // method, which the server returns.
        assertThat(
                references("/org/javacs/example/AnnotationElementDef.java", 4, 13),
                containsInAnyOrder(
                        "AnnotationElementDef.java:4",
                        "AnnotationElementShorthand.java:3",
                        "AnnotationElementUse.java:3",
                        "AnnotationElementUse.java:5"));
    }

    @Test
    public void inheritedMethodCallViaSubclass() {
        // Sub does not declare m(), so b.m() is attributed to the inherited base method.
        assertThat(
                references("/org/javacs/example/RefInheritedMethodCall.java", 4, 10),
                containsInAnyOrder(
                        "RefInheritedMethodCall.java:4", "RefInheritedMethodCall.java:12"));
    }

    @Test
    public void directOverrideCall() {
        // b.m() is attributed to the override. The server treats an overridden declaration as a
        // reference to the override too, so the base declaration on line 4 is part of the exact set.
        assertThat(
                references("/org/javacs/example/RefDirectOverride.java", 9, 10),
                containsInAnyOrder(
                        "RefDirectOverride.java:4",
                        "RefDirectOverride.java:9",
                        "RefDirectOverride.java:15"));
    }

    @Test
    public void abstractMethodCalls() {
        assertThat(
                references("/org/javacs/example/RefAbstractMethod.java", 4, 19),
                containsInAnyOrder("RefAbstractMethod.java:4", "RefAbstractMethod.java:9"));
    }

    @Test
    public void interfaceMethodViaInterfaceType() {
        assertThat(
                references("/org/javacs/example/RefInterfaceMethod.java", 4, 10),
                containsInAnyOrder("RefInterfaceMethod.java:4", "RefInterfaceMethod.java:9"));
    }

    @Test
    public void interfaceMethodViaImplementationType() {
        // The abstract implementation declares no m(), so javac selects the interface method.
        assertThat(
                references("/org/javacs/example/RefInterfaceViaImpl.java", 4, 10),
                containsInAnyOrder("RefInterfaceViaImpl.java:4", "RefInterfaceViaImpl.java:11"));
    }

    @Test
    public void defaultMethodInherited() {
        // The implementation does not override the default, so c.m() is I.m.
        assertThat(
                references("/org/javacs/example/RefDefaultInherited.java", 4, 18),
                containsInAnyOrder("RefDefaultInherited.java:4", "RefDefaultInherited.java:11"));
    }

    @Test
    public void defaultMethodOverridden() {
        // The implementation's override is the target of c.m(). Override scanning also returns the
        // overridden default declaration on line 4.
        assertThat(
                references("/org/javacs/example/RefDefaultOverridden.java", 8, 17),
                containsInAnyOrder(
                        "RefDefaultOverridden.java:4",
                        "RefDefaultOverridden.java:8",
                        "RefDefaultOverridden.java:13"));
    }

    @Test
    public void interfaceStaticMethodCall() {
        // Interface static methods are called through the interface name only.
        assertThat(
                references("/org/javacs/example/RefInterfaceStatic.java", 4, 17),
                containsInAnyOrder("RefInterfaceStatic.java:4", "RefInterfaceStatic.java:9"));
    }

    @Test
    public void interfacePrivateMethodCall() {
        // A private interface method is visible only inside its own file.
        assertThat(
                references("/org/javacs/example/RefInterfacePrivate.java", 4, 18),
                containsInAnyOrder("RefInterfacePrivate.java:4", "RefInterfacePrivate.java:7"));
    }

    @Test
    public void superMethodCall() {
        // super.m() bypasses the override and calls the base declaration directly. Scanning the
        // base method also returns the override declaration on line 8.
        assertThat(
                references("/org/javacs/example/RefSuperMethodCall.java", 4, 10),
                containsInAnyOrder(
                        "RefSuperMethodCall.java:4",
                        "RefSuperMethodCall.java:8",
                        "RefSuperMethodCall.java:9"));
    }

    @Test
    public void interfaceSuperCall() {
        // I.super.m() selects the default method, not the implementation's override. Scanning the
        // default method also returns the override declaration on line 8.
        assertThat(
                references("/org/javacs/example/RefInterfaceSuperCall.java", 4, 18),
                containsInAnyOrder(
                        "RefInterfaceSuperCall.java:4",
                        "RefInterfaceSuperCall.java:8",
                        "RefInterfaceSuperCall.java:9"));
    }

    @Test
    public void staticMethodHiding() {
        // Static methods are hidden, not overridden: each call stays with its own declaration.
        assertThat(
                references("/org/javacs/example/RefStaticMethodHiding.java", 4, 17),
                containsInAnyOrder(
                        "RefStaticMethodHiding.java:4", "RefStaticMethodHiding.java:13"));
        assertThat(
                references("/org/javacs/example/RefStaticMethodHiding.java", 8, 17),
                containsInAnyOrder(
                        "RefStaticMethodHiding.java:8", "RefStaticMethodHiding.java:14"));
    }

    @Test
    public void privateMethodNotOverridden() {
        // The private base method is not inherited, so sub.m() resolves to the subclass method.
        assertThat(
                references("/org/javacs/example/RefPrivateMethodNotOverridden.java", 8, 10),
                containsInAnyOrder(
                        "RefPrivateMethodNotOverridden.java:8", "RefPrivateMethodNotOverridden.java:14"));
    }

    @Test
    public void genericOverride() {
        // B.m(String) overrides A.m(T) after substituting T = String. Override scanning also
        // returns the base declaration on line 4. The synthetic bridge method has no source tree,
        // so it cannot add another location.
        assertThat(
                references("/org/javacs/example/RefGenericOverride.java", 8, 10),
                containsInAnyOrder(
                        "RefGenericOverride.java:4",
                        "RefGenericOverride.java:8",
                        "RefGenericOverride.java:14"));
    }

    @Test
    public void localShadowsField() {
        // The field cursor matches only the qualified use; the local cursor matches only the bare use.
        assertThat(
                references("/org/javacs/example/RefLocalShadowsField.java", 4, 17),
                containsInAnyOrder("RefLocalShadowsField.java:12"));
        assertThat(
                references("/org/javacs/example/RefLocalShadowsField.java", 7, 13),
                containsInAnyOrder("RefLocalShadowsField.java:8"));
    }

    @Test
    public void parameterShadowsField() {
        // Bare `f` is the parameter; `this.f` is the field.
        assertThat(
                references("/org/javacs/example/RefParameterShadowsField.java", 4, 17),
                containsInAnyOrder("RefParameterShadowsField.java:11"));
        assertThat(
                references("/org/javacs/example/RefParameterShadowsField.java", 6, 18),
                containsInAnyOrder("RefParameterShadowsField.java:7"));
    }

    @Test
    public void lambdaParameterShadowsField() {
        // The field only appears as `this.x`; the lambda parameter appears as the two bare uses.
        assertThat(
                references("/org/javacs/example/RefLambdaParameterShadowsField.java", 4, 17),
                containsInAnyOrder("RefLambdaParameterShadowsField.java:8"));
        assertThat(
                references("/org/javacs/example/RefLambdaParameterShadowsField.java", 7, 16),
                containsInAnyOrder(
                        "RefLambdaParameterShadowsField.java:8",
                        "RefLambdaParameterShadowsField.java:9"));
    }

    @Test
    public void inheritedFieldHiding() {
        // Bare `f` and `this.f` in Sub are Sub.f; `super.f` and the cast receiver are Super.f.
        assertThat(
                references("/org/javacs/example/RefInheritedFieldSub.java", 4, 9),
                containsInAnyOrder(
                        "RefInheritedFieldSub.java:7", "RefInheritedFieldSub.java:8"));
        assertThat(
                references("/org/javacs/example/RefInheritedFieldSuper.java", 4, 9),
                containsInAnyOrder(
                        "RefInheritedFieldSub.java:9", "RefInheritedFieldSub.java:10"));
    }

    @Test
    public void nestedTypeShadowsTopLevel() {
        // The simple name inside Outer is the member type; the fully qualified name is the
        // top-level type in the other package.
        assertThat(
                references("/org/javacs/example/RefNestedTypeOuter.java", 6, 5),
                containsInAnyOrder(
                        "RefNestedTypeOuter.java:6", "RefNestedTypeOuter.java:11"));
        assertThat(
                references("/org/javacs/example/RefNestedTypeOuter.java", 8, 22),
                containsInAnyOrder(
                        "RefNestedTypeOuter.java:8", "RefNestedTypeOuter.java:12"));
    }

    @Test
    public void typeParameterShadowsClass() {
        // Inside Box, T is the type parameter; outside, T is the top-level class in the same package.
        assertThat(
                references("/org/javacs/example/Box.java", 4, 5),
                containsInAnyOrder("Box.java:4", "Box.java:6"));
        assertThat(
                references("/org/javacs/example/RefTypeParamShadowsClassUse.java", 4, 5),
                containsInAnyOrder("RefTypeParamShadowsClassUse.java:4"));
    }

    @Test
    public void sameSimpleNameAcrossPackages() {
        // Both classes are named C but live in different packages; each scan stays with its own type.
        assertThat(
                references("/org/javacs/example/RefSameSimpleName.java", 4, 7),
                containsInAnyOrder("RefSameSimpleName.java:4", "RefSameSimpleName.java:9"));
        assertThat(
                references("/org/javacs/example/RefSameSimpleName.java", 6, 7),
                containsInAnyOrder("RefSameSimpleName.java:6", "RefSameSimpleName.java:10"));
    }

    @Test
    public void anonymousFieldShadowsOuter() {
        // Bare `f` in the anonymous body is the inner field; the qualified `Outer.this.f` is the outer.
        assertThat(
                references("/org/javacs/example/RefAnonymousFieldOuter.java", 4, 17),
                containsInAnyOrder("RefAnonymousFieldOuter.java:12"));
        assertThat(
                references("/org/javacs/example/RefAnonymousFieldOuter.java", 8, 25),
                containsInAnyOrder("RefAnonymousFieldOuter.java:11"));
    }

    @Test
    public void thisQualifiedMethod() {
        // The unqualified call and `this.m()` both target the same method.
        assertThat(
                references("/org/javacs/example/RefThisMethod.java", 4, 10),
                containsInAnyOrder("RefThisMethod.java:4", "RefThisMethod.java:7", "RefThisMethod.java:8"));
    }

    @Test
    public void superQualifiedField() {
        // The subclass field f hides the base field, so only `super.f` refers to the base field.
        assertThat(
                references("/org/javacs/example/RefSuperFieldAccess.java", 4, 9),
                containsInAnyOrder("RefSuperFieldAccess.java:13"));
    }

    @Test
    public void typeQualifiedStaticMember() {
        // The field declaration is not returned; the static method declaration is.
        assertThat(
                references("/org/javacs/example/RefTypeQualifiedStatic.java", 4, 16),
                containsInAnyOrder("RefTypeQualifiedStatic.java:9"));
        assertThat(
                references("/org/javacs/example/RefTypeQualifiedStatic.java", 6, 17),
                containsInAnyOrder("RefTypeQualifiedStatic.java:6", "RefTypeQualifiedStatic.java:10"));
    }

    @Test
    public void instanceQualifiedMethod() {
        assertThat(
                references("/org/javacs/example/RefInstanceQualified.java", 4, 10),
                containsInAnyOrder("RefInstanceQualified.java:4", "RefInstanceQualified.java:7"));
    }

    @Test
    public void castReceiver() {
        // The cast receiver selects Sub.m. Base.m's declaration is also part of the set because the
        // server treats an overridden declaration as a reference to the override.
        assertThat(
                references("/org/javacs/example/RefCastReceiver.java", 8, 10),
                containsInAnyOrder(
                        "RefCastReceiver.java:4", "RefCastReceiver.java:8", "RefCastReceiver.java:13"));
    }

    @Test
    public void chainedCall() {
        // Each link resolves independently: b appears as the a.b() select, c as the .c() select.
        assertThat(
                references("/org/javacs/example/RefChainedCall.java", 4, 17),
                containsInAnyOrder("RefChainedCall.java:4", "RefChainedCall.java:21"));
        assertThat(
                references("/org/javacs/example/RefChainedCall.java", 10, 17),
                containsInAnyOrder("RefChainedCall.java:10", "RefChainedCall.java:21"));
    }

    @Test
    public void arrayElementReceiver() {
        assertThat(
                references("/org/javacs/example/RefArrayElementReceiver.java", 4, 10),
                containsInAnyOrder(
                        "RefArrayElementReceiver.java:4", "RefArrayElementReceiver.java:7"));
    }

    @Test
    public void methodReferenceUnbound() {
        // C::m passes the receiver as the functional method's parameter.
        assertThat(
                references("/org/javacs/example/RefUnboundMethodReference.java", 8, 10),
                containsInAnyOrder(
                        "RefUnboundMethodReference.java:8", "RefUnboundMethodReference.java:13"));
    }

    @Test
    public void methodReferenceBound() {
        // c::m captures the receiver.
        assertThat(
                references("/org/javacs/example/RefBoundMethodReference.java", 8, 10),
                containsInAnyOrder(
                        "RefBoundMethodReference.java:8", "RefBoundMethodReference.java:13"));
    }

    @Test
    public void methodReferenceThisSuper() {
        // this::m targets the override, super::m targets the base. The overridden declaration is
        // part of both sets; each method reference belongs to only one target.
        assertThat(
                references("/org/javacs/example/RefThisSuperMethodReference.java", 8, 10),
                containsInAnyOrder(
                        "RefThisSuperMethodReference.java:8",
                        "RefThisSuperMethodReference.java:12",
                        "RefThisSuperMethodReference.java:19"));
        assertThat(
                references("/org/javacs/example/RefThisSuperMethodReference.java", 12, 10),
                containsInAnyOrder(
                        "RefThisSuperMethodReference.java:8",
                        "RefThisSuperMethodReference.java:12",
                        "RefThisSuperMethodReference.java:15"));
    }

    @Test
    public void methodReferenceOverload() {
        // The functional interface takes an int, so C::m selects m(int); m(long) must not appear.
        assertThat(
                references("/org/javacs/example/RefMethodReferenceOverload.java", 4, 16),
                containsInAnyOrder(
                        "RefMethodReferenceOverload.java:4", "RefMethodReferenceOverload.java:13"));
    }

    @Test
    public void arrayConstructorReference() {
        // C[]::new is a type reference to C, not a constructor reference. The interface's array
        // return type is the second type reference in the file.
        assertThat(
                references("/org/javacs/example/RefArrayConstructorReference.java", 10, 25),
                containsInAnyOrder(
                        "RefArrayConstructorReference.java:4", "RefArrayConstructorReference.java:10"));
    }

    @Test
    public void singleStaticImportMethod() {
        // The import names the member, so the import line itself is a reference to the imported method.
        assertThat(
                references("/org/javacs/example/RefStaticImportMethod.java", 3, 55),
                containsInAnyOrder(
                        "RefImportTarget.java:6",
                        "RefStaticImportMethod.java:3",
                        "RefStaticImportMethod.java:7"));
    }

    @Test
    public void singleStaticImportField() {
        // A field has no declaration location: only the import line and the use are returned.
        assertThat(
                references("/org/javacs/example/RefStaticImportField.java", 3, 55),
                containsInAnyOrder("RefStaticImportField.java:3", "RefStaticImportField.java:7"));
    }

    @Test
    public void onDemandStaticImport() {
        // A wildcard import names no member, so the `.*` line is not a reference. The method
        // declaration is returned; the field declaration is not.
        assertThat(
                references("/org/javacs/example/RefStaticImportOnDemand.java", 7, 15),
                containsInAnyOrder("RefImportTarget.java:8", "RefStaticImportOnDemand.java:7"));
        assertThat(
                references("/org/javacs/example/RefStaticImportOnDemand.java", 11, 25),
                containsInAnyOrder("RefStaticImportOnDemand.java:11"));
    }

    @Test
    public void staticImportEnumConstant() {
        // The enum constant's declaration is a field, so it is not returned.
        assertThat(
                references("/org/javacs/example/RefStaticImportEnumConstant.java", 6, 29),
                containsInAnyOrder(
                        "RefStaticImportEnumConstant.java:3", "RefStaticImportEnumConstant.java:6"));
    }

    @Test
    public void staticImportInheritedMember() {
        // Sub declares nothing, so both the imported name and the call resolve to Super.m.
        assertThat(
                references("/org/javacs/example/RefStaticImportInheritedMember.java", 7, 15),
                containsInAnyOrder(
                        "RefStaticImportSuper.java:4",
                        "RefStaticImportInheritedMember.java:3",
                        "RefStaticImportInheritedMember.java:7"));
    }

    @Test
    public void staticImportShadowedByLocalMethod() {
        // The class's own shadowedMethod hides the single-static-import, so the import line must not
        // appear in the scan for the local declaration and call. The imported method is not asserted.
        assertThat(
                references("/org/javacs/example/RefStaticImportShadowedByLocalMethod.java", 6, 15),
                containsInAnyOrder(
                        "RefStaticImportShadowedByLocalMethod.java:6",
                        "RefStaticImportShadowedByLocalMethod.java:9"));
    }

    @Test
    public void singleTypeImport() {
        // The explicit import names the type, so both the import line and the use are references.
        assertThat(
                references("/org/javacs/example/RefSingleTypeImport.java", 3, 30),
                containsInAnyOrder("RefSingleTypeImport.java:3", "RefSingleTypeImport.java:6"));
    }

    @Test
    public void onDemandTypeImport() {
        // The package wildcard names no type, so only the simple-name use is returned.
        assertThat(
                references("/org/javacs/example/RefOnDemandTypeImport.java", 6, 10),
                containsInAnyOrder("RefOnDemandTypeImport.java:6"));
    }

    @Test
    public void nestedTypeImport() {
        // The import names Outer.Inner, so the import line and the simple-name use both match.
        assertThat(
                references("/org/javacs/example/RefNestedTypeImport.java", 3, 41),
                containsInAnyOrder("RefNestedTypeImport.java:3", "RefNestedTypeImport.java:6"));
    }

    @Test
    public void includeTrueDeclaration() {
        // Cursor is on the declaration; includeDeclaration=true adds it to the two uses.
        assertThat(
                references("/org/javacs/example/RefIncludeDeclaration.java", 9, 18, true),
                containsInAnyOrder(
                        "RefIncludeDeclaration.java:5",
                        "RefIncludeDeclaration.java:6",
                        "RefIncludeDeclaration.java:9"));
    }

    @Ignore("includeDeclaration=false is not honored yet")
    @Test
    public void includeFalseDeclaration() {
        // Same cursor as includeTrueDeclaration; the declaration line must not appear.
        assertThat(
                references("/org/javacs/example/RefIncludeDeclaration.java", 9, 18, false),
                containsInAnyOrder("RefIncludeDeclaration.java:5", "RefIncludeDeclaration.java:6"));
    }

    @Test
    public void includeTrueUsage() {
        // Cursor is on the first use; includeDeclaration=true expects the declaration and both uses.
        assertThat(
                references("/org/javacs/example/RefIncludeDeclaration.java", 5, 9, true),
                containsInAnyOrder(
                        "RefIncludeDeclaration.java:5",
                        "RefIncludeDeclaration.java:6",
                        "RefIncludeDeclaration.java:9"));
    }

    @Ignore("includeDeclaration=false is not honored yet")
    @Test
    public void includeFalseUsage() {
        // Same cursor as includeTrueUsage; only uses are expected, including the cursor occurrence.
        assertThat(
                references("/org/javacs/example/RefIncludeDeclaration.java", 5, 9, false),
                containsInAnyOrder("RefIncludeDeclaration.java:5", "RefIncludeDeclaration.java:6"));
    }

    @Test
    public void unusedSymbolIncludesDeclaration() {
        // The private method is never called, so the declaration is its only reference.
        assertThat(
                references("/org/javacs/example/RefUnusedMethod.java", 4, 18, true),
                containsInAnyOrder("RefUnusedMethod.java:4"));
    }

    @Ignore("includeDeclaration=false is not honored yet")
    @Test
    public void unusedSymbolExcludingDeclaration() {
        assertThat(references("/org/javacs/example/RefUnusedMethod.java", 4, 18, false), empty());
    }

    @Test
    public void declarationInAnotherFileIncludesDeclaration() {
        // The cursor is on the declaration in A; the two uses live in B.
        assertThat(
                references("/org/javacs/example/RefCrossFileDeclaration.java", 4, 18, true),
                containsInAnyOrder(
                        "RefCrossFileDeclaration.java:4",
                        "RefCrossFileUse.java:5",
                        "RefCrossFileUse.java:6"));
    }

    @Ignore("includeDeclaration=false is not honored yet")
    @Test
    public void declarationInAnotherFileExcludingDeclaration() {
        assertThat(
                references("/org/javacs/example/RefCrossFileDeclaration.java", 4, 18, false),
                containsInAnyOrder("RefCrossFileUse.java:5", "RefCrossFileUse.java:6"));
    }

    @Test
    public void commentMention() {
        // Comments are not part of the attributed tree, so a name mentioned in a comment resolves
        // no symbol.
        assertThat(references("/org/javacs/example/RefCommentMention.java", 6, 8), empty());
        assertThat(references("/org/javacs/example/RefCommentMention.java", 11, 8), empty());
    }

    @Test
    public void stringLiteralMention() {
        // The `f` inside the string literal is text, not an identifier.
        assertThat(references("/org/javacs/example/RefStringMention.java", 6, 20), empty());
    }

    @Test
    public void textBlockMention() {
        // The `f` inside the text block is text, not an identifier.
        assertThat(references("/org/javacs/example/RefTextBlockMention.java", 7, 13), empty());
    }

    @Test
    public void javadocPlainText() {
        // Javadoc prose is not a tree node; only {@link} tags name a symbol.
        assertThat(references("/org/javacs/example/RefJavadocPlainText.java", 4, 13), empty());
        assertThat(references("/org/javacs/example/RefJavadocPlainText.java", 10, 16), empty());
    }

    @Test
    public void unresolvedName() {
        // CantResolve.java has a compile error; the undeclared `totals` must not throw, and the
        // error element must not match any identifier in the file.
        assertThat(references("/org/javacs/err/CantResolve.java", 5, 17), empty());
    }

    @Test
    public void unrelatedLocalSameName() {
        // Each method declares its own `x`; the other method's uses must not appear.
        assertThat(
                references("/org/javacs/example/RefUnrelatedLocalSameName.java", 5, 13),
                containsInAnyOrder(
                        "RefUnrelatedLocalSameName.java:6", "RefUnrelatedLocalSameName.java:6"));
        assertThat(
                references("/org/javacs/example/RefUnrelatedLocalSameName.java", 10, 13),
                containsInAnyOrder(
                        "RefUnrelatedLocalSameName.java:11", "RefUnrelatedLocalSameName.java:11"));
    }

    @Test
    public void arrayLengthPseudoField() {
        // `a.length` is the array's pseudo-field, not the class field named length.
        var found = references("/org/javacs/example/RefArrayLengthField.java", 4, 17);
        assertThat(
                found,
                containsInAnyOrder("RefArrayLengthField.java:11", "RefArrayLengthField.java:11"));
        assertThat(found, not(hasItem("RefArrayLengthField.java:7")));
    }

    @Test
    public void primitiveClassLiteral() {
        // `int` in `int.class` is a primitive type, not a reference to a declared type.
        assertThat(references("/org/javacs/example/RefPrimitiveClassLiteral.java", 4, 21), empty());
    }

    @Test
    public void thisSuperKeywords() {
        // `this` and `super` are keywords; javac attributes them to synthetic fields, but they are
        // not references to a declared entity.
        assertThat(references("/org/javacs/example/RefThisSuperKeyword.java", 7, 16), empty());
        assertThat(references("/org/javacs/example/RefThisSuperKeyword.java", 11, 16), empty());
    }

    @Test
    public void valueOfStringLiteral() {
        // The string "RED" passed to valueOf is not a reference to the enum constant.
        assertThat(
                references("/org/javacs/example/RefValueOfStringLiteral.java", 9, 32),
                containsInAnyOrder("RefValueOfStringLiteral.java:9"));
        // A cursor inside the literal resolves no symbol.
        assertThat(references("/org/javacs/example/RefValueOfStringLiteral.java", 5, 41), empty());
    }

    @Test
    public void privateMemberFromNestedClass() {
        // A private field and method are visible throughout the top-level class body, including
        // the nested class. Fields do not include their declaration; methods do.
        assertThat(
                references("/org/javacs/example/RefPrivateNestedMembers.java", 4, 17),
                containsInAnyOrder("RefPrivateNestedMembers.java:10"));
        assertThat(
                references("/org/javacs/example/RefPrivateNestedMembers.java", 6, 18),
                containsInAnyOrder(
                        "RefPrivateNestedMembers.java:6", "RefPrivateNestedMembers.java:11"));
    }

    @Test
    public void packagePrivateTypeSamePackage() {
        // The declaration is not returned for types. The fully-qualified use of the same-named
        // type in org.javacs.other on line 10 must not leak into the scan.
        assertThat(
                references("/org/javacs/example/PackagePrivateType.java", 3, 10),
                containsInAnyOrder(
                        "PackagePrivateTypeUse.java:4",
                        "PackagePrivateTypeUse.java:6",
                        "PackagePrivateTypeUse.java:7"));
    }

    @Test
    public void publicTypeCrossPackageImport() {
        // The explicit import line names the type, so it is a reference along with the uses.
        assertThat(
                references("/org/javacs/example/RefCrossPackagePublicTypeUse.java", 6, 10),
                containsInAnyOrder(
                        "RefCrossPackagePublicTypeUse.java:3",
                        "RefCrossPackagePublicTypeUse.java:6",
                        "RefCrossPackagePublicTypeUse.java:8",
                        "RefCrossPackagePublicTypeUse.java:9"));
    }

    @Test
    public void interfaceConstantCrossPackage() {
        // The cursor is on the interface constant declaration. Fields have no declaration
        // location: only the static import line and the bare use are expected.
        assertThat(
                references("/org/javacs/other/I.java", 4, 9),
                containsInAnyOrder(
                        "InterfaceConstantCrossPackage.java:3",
                        "InterfaceConstantCrossPackage.java:7"));
    }

    @Test
    public void lombokFieldReferences() {
        assertThat(
                references("/org/javacs/example/LombokFieldReferences.java", 7, 20),
                containsInAnyOrder(
                        "LombokFieldReferences.java:10",
                        "LombokFieldReferences.java:11",
                        "LombokFieldReferences.java:12",
                        "LombokFieldReferences.java:13"));
    }

    @Test
    public void lombokAccessorReferences() {
        assertThat(
                references("/org/javacs/example/LombokFieldReferences.java", 10, 13),
                containsInAnyOrder(
                        "LombokFieldReferences.java:10",
                        "LombokFieldReferences.java:11",
                        "LombokFieldReferences.java:12",
                        "LombokFieldReferences.java:13"));
    }

    @Test
    public void lombokInheritedAccessorReferences() {
        assertThat(
                references("/org/javacs/example/LombokInheritedPojoMembers.java", 14, 34),
                containsInAnyOrder("LombokInheritedPojoMembers.java:8"));
        assertThat(
                references("/org/javacs/example/LombokInheritedAccessorNavigation.java", 15, 13),
                containsInAnyOrder("LombokInheritedAccessorNavigation.java:15"));
    }

    @Test
    public void lombokBuilderReferences() {
        assertThat(
                references("/org/javacs/example/LombokBuilderTest.java", 9, 20),
                containsInAnyOrder("LombokBuilderTest.java:15"));
    }

    @Test
    public void lombokCrossFileAccessorReferences() {
        assertThat(
                references("/org/javacs/example/LombokCrossTypeModel.java", 7, 20),
                containsInAnyOrder(
                        "LombokCrossTypeDiagnostics.java:6",
                        "LombokCrossTypeDiagnostics.java:7"));
        assertThat(
                references("/org/javacs/example/LombokNestedBar.java", 7, 20),
                containsInAnyOrder("LombokNestedDiagnostics.java:6"));
    }

    private List<String> references(String file, int row, int column) {
        // Existing callers rely on includeDeclaration=true. The server currently ignores the flag:
        // methods include their declaration, fields and types do not.
        return references(file, row, column, true);
    }

    private List<String> references(String file, int row, int column, boolean includeDeclaration) {
        var params = new ReferenceParams();
        params.textDocument = new TextDocumentIdentifier();
        var path = FindResource.path(file);
        params.textDocument.uri = path.toUri();
        params.position = new Position();
        params.position.line = row - 1;
        params.position.character = column - 1;
        params.context = new ReferenceContext();
        params.context.includeDeclaration = includeDeclaration;

        server.completionIndexScheduler.ensureIndexed(path);
        server.completionIndexScheduler.awaitReady(10_000);

        var locations = server.findReferences(params).orElse(List.of());
        var strings = new ArrayList<String>();
        for (var location : locations) {
            var fileName = Paths.get(location.uri).getFileName().toString();
            strings.add(String.format("%s:%d", fileName, location.range.start.line + 1));
        }
        return strings;
    }

    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @AfterClass
    public static void shutdownServer() {
        server.shutdown();
    }
}
