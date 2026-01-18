package org.javacs;

import com.sun.source.tree.*;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

/**
 * Tests for Lombok annotation analysis and member generation.
 * Verifies that LombokSupport correctly analyzes ClassTree and generates metadata for various Lombok annotations.
 */
public class LombokSupportTest {
    private TestCompiler compiler;

    @Before
    public void setup() {
        compiler = new TestCompiler();
    }

    /**
     * Test @Data annotation generates all expected member flags.
     * @Data generates: getters, setters, toString, equals, hashCode, requiredArgsConstructor
     */
    @Test
    public void testAnalyzeDataAnnotation() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Data;\n" +
                "@Data\n" +
                "public class TestClass {\n" +
                "    private String name;\n" +
                "    private int age;\n" +
                "    private boolean active;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.hasData, is(true));
        assertThat(metadata.hasGetter, is(true));
        assertThat(metadata.hasSetter, is(true));
        assertThat(metadata.hasToString, is(true));
        assertThat(metadata.hasEqualsAndHashCode, is(true));
        assertThat(metadata.hasAllArgsConstructor, is(false));
        assertThat(metadata.hasRequiredArgsConstructor, is(true));
    }

    /**
     * Test getter name generation for regular fields.
     */
    @Test
    public void testGetterNameForRegularField() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Getter;\n" +
                "@Getter\n" +
                "public class TestClass {\n" +
                "    private String name;\n" +
                "    private int age;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);
        var getterNames = metadata.getGeneratedGetterNames();

        assertThat(getterNames, hasItem("getName"));
        assertThat(getterNames, hasItem("getAge"));
    }

    /**
     * Test getter name generation for boolean fields.
     */
    @Test
    public void testGetterNameForBooleanField() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Getter;\n" +
                "@Getter\n" +
                "public class TestClass {\n" +
                "    private boolean active;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);
        var getterNames = metadata.getGeneratedGetterNames();

        assertThat(getterNames, hasItem("isActive"));
        assertThat(getterNames, not(hasItem("getActive")));
    }

    @Test
    public void testGetterNameForBooleanIsPrefix() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Getter;\n" +
                "@Getter\n" +
                "public class TestClass {\n" +
                "    private boolean isActive;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);
        var getterNames = metadata.getGeneratedGetterNames();

        assertThat(getterNames, hasItem("isActive"));
        assertThat(getterNames, not(hasItem("getIsActive")));
    }

    @Test
    public void testSetterNameForBooleanIsPrefix() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Setter;\n" +
                "@Setter\n" +
                "public class TestClass {\n" +
                "    private boolean isActive;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);
        var setterNames = metadata.getGeneratedSetterNames();

        assertThat(setterNames, hasItem("setActive"));
        assertThat(setterNames, not(hasItem("setIsActive")));
    }

    @Test
    public void testGetterNameCapitalization() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Getter;\n" +
                "@Getter\n" +
                "public class TestClass {\n" +
                "    private String URL;\n" +
                "    private String uRL;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);
        var getterNames = metadata.getGeneratedGetterNames();

        assertThat(getterNames, hasItem("getURL"));
    }

    /**
     * Test setter name generation.
     */
    @Test
    public void testSetterNameGeneration() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Setter;\n" +
                "@Setter\n" +
                "public class TestClass {\n" +
                "    private String name;\n" +
                "    private int age;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);
        var setterNames = metadata.getGeneratedSetterNames();

        assertThat(setterNames, hasItem("setName"));
        assertThat(setterNames, hasItem("setAge"));
    }

    @Test
    public void testFinalFieldHasNoSetter() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Setter;\n" +
                "@Setter\n" +
                "public class TestClass {\n" +
                "    private final String name = \"x\";\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);
        var setterNames = metadata.getGeneratedSetterNames();

        assertThat(setterNames, not(hasItem("setName")));
    }

    @Test
    public void testFieldLevelGetterSetter() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Getter;\n" +
                "import lombok.Setter;\n" +
                "public class TestClass {\n" +
                "    @Getter @Setter private String name;\n" +
                "    private int age;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);
        var getterNames = metadata.getGeneratedGetterNames();
        var setterNames = metadata.getGeneratedSetterNames();

        assertThat(getterNames, hasItem("getName"));
        assertThat(getterNames, not(hasItem("getAge")));
        assertThat(setterNames, hasItem("setName"));
        assertThat(setterNames, not(hasItem("setAge")));
    }

    @Test
    public void testExplicitGetterPreventsSyntheticDuplicate() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Getter;\n" +
                "@Getter\n" +
                "public class TestClass {\n" +
                "    private String name;\n" +
                "    public String getName() { return name; }\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);
        var getterNames = metadata.getGeneratedGetterNames();

        assertThat(getterNames, not(hasItem("getName")));
    }

    /**
     * Test that isGeneratedGetter() correctly identifies getter methods.
     */
    @Test
    public void testIsGeneratedGetter() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Data;\n" +
                "@Data\n" +
                "public class TestClass {\n" +
                "    private String name;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.isGeneratedGetter("getName"), is(true));
        assertThat(metadata.isGeneratedGetter("toString"), is(false));
    }

    /**
     * Test that isGeneratedSetter() correctly identifies setter methods.
     */
    @Test
    public void testIsGeneratedSetter() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Data;\n" +
                "@Data\n" +
                "public class TestClass {\n" +
                "    private String name;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.isGeneratedSetter("setName"), is(true));
        assertThat(metadata.isGeneratedSetter("getName"), is(false));
    }

    /**
     * Test that fieldForGetter() returns the correct field for a getter method.
     */
    @Test
    public void testFieldForGetter() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Data;\n" +
                "@Data\n" +
                "public class TestClass {\n" +
                "    private String name;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);
        var field = metadata.fieldForGetter("getName");

        assertThat(field, notNullValue());
        assertThat(field.getName().toString(), is("name"));
    }

    /**
     * Test that fieldForSetter() returns the correct field for a setter method.
     */
    @Test
    public void testFieldForSetter() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Data;\n" +
                "@Data\n" +
                "public class TestClass {\n" +
                "    private String name;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);
        var field = metadata.fieldForSetter("setName");

        assertThat(field, notNullValue());
        assertThat(field.getName().toString(), is("name"));
    }

    /**
     * Test that isGeneratedSpecialMethod() identifies toString, equals, hashCode.
     */
    @Test
    public void testIsGeneratedSpecialMethod() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Data;\n" +
                "@Data\n" +
                "public class TestClass {\n" +
                "    private String name;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.isGeneratedSpecialMethod("toString"), is(true));
        assertThat(metadata.isGeneratedSpecialMethod("equals"), is(true));
        assertThat(metadata.isGeneratedSpecialMethod("hashCode"), is(true));
        assertThat(metadata.isGeneratedSpecialMethod("clone"), is(false));
    }

    /**
     * Test constructor parameter generation for @AllArgsConstructor.
     */
    @Test
    public void testConstructorParameterNames() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.AllArgsConstructor;\n" +
                "import lombok.Getter;\n" +
                "@Getter\n" +
                "@AllArgsConstructor\n" +
                "public class TestClass {\n" +
                "    private String name;\n" +
                "    private int age;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);
        var params = metadata.getConstructorParameterNames();

        assertThat(metadata.hasAllArgsConstructor, is(true));
        assertThat(params.size(), is(2));
        assertThat(params.get(0), is("name"));
        assertThat(params.get(1), is("age"));
    }

    /**
     * Test that hasLombokAnnotations() returns true for @Data.
     */
    @Test
    public void testHasLombokAnnotations() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Data;\n" +
                "@Data\n" +
                "public class TestClass {\n" +
                "    private String name;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(LombokSupport.hasLombokAnnotations(metadata), is(true));
    }

    /**
     * Test field extraction.
     */
    @Test
    public void testFieldExtraction() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Data;\n" +
                "@Data\n" +
                "public class TestClass {\n" +
                "    private String name;\n" +
                "    private int age;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.allFields.size(), is(2));
        assertThat(metadata.fieldsByName.keySet(), hasItems("name", "age"));
    }

    /**
     * Test that a class without Lombok annotations has all flags false.
     */
    @Test
    public void testNonLombokClass() {
        var classTree = compiler.compile(
                "package test;\n" +
                "public class TestClass {\n" +
                "    private String name;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.hasData, is(false));
        assertThat(metadata.hasGetter, is(false));
        assertThat(LombokSupport.hasLombokAnnotations(metadata), is(false));
    }

    /**
     * Test that null ClassTree returns empty metadata.
     */
    @Test
    public void testNullClassTree() {
        var metadata = LombokSupport.analyze(null);

        assertThat(metadata.allFields.isEmpty(), is(true));
        assertThat(LombokSupport.hasLombokAnnotations(metadata), is(false));
    }

    /**
     * Test @Getter annotation.
     */
    @Test
    public void testGetterAnnotation() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Getter;\n" +
                "@Getter\n" +
                "public class TestClass {\n" +
                "    private String value;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.hasGetter, is(true));
        assertThat(metadata.hasSetter, is(false));
    }

    /**
     * Test @Setter annotation.
     */
    @Test
    public void testSetterAnnotation() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Setter;\n" +
                "@Setter\n" +
                "public class TestClass {\n" +
                "    private String value;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.hasSetter, is(true));
        assertThat(metadata.hasGetter, is(false));
    }

    /**
     * Test @ToString annotation.
     */
    @Test
    public void testToStringAnnotation() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.ToString;\n" +
                "@ToString\n" +
                "public class TestClass {\n" +
                "    private String value;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.hasToString, is(true));
        assertThat(metadata.isGeneratedSpecialMethod("toString"), is(true));
    }

    /**
     * Test @EqualsAndHashCode annotation.
     */
    @Test
    public void testEqualsAndHashCodeAnnotation() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.EqualsAndHashCode;\n" +
                "@EqualsAndHashCode\n" +
                "public class TestClass {\n" +
                "    private String value;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.hasEqualsAndHashCode, is(true));
        assertThat(metadata.isGeneratedSpecialMethod("equals"), is(true));
        assertThat(metadata.isGeneratedSpecialMethod("hashCode"), is(true));
    }

    /**
     * Test @Value annotation (immutable).
     */
    @Test
    public void testValueAnnotation() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Value;\n" +
                "@Value\n" +
                "public class TestClass {\n" +
                "    String name;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.hasValue, is(true));
        assertThat(metadata.hasGetter, is(true));
        assertThat(metadata.hasSetter, is(false));  // @Value is immutable
        assertThat(metadata.hasToString, is(true));
        assertThat(metadata.hasEqualsAndHashCode, is(true));
    }

    /**
     * Test @NoArgsConstructor annotation.
     */
    @Test
    public void testNoArgsConstructorAnnotation() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.NoArgsConstructor;\n" +
                "@NoArgsConstructor\n" +
                "public class TestClass {\n" +
                "    private String value;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.hasNoArgsConstructor, is(true));
    }

    /**
     * Test @RequiredArgsConstructor annotation.
     */
    @Test
    public void testRequiredArgsConstructorAnnotation() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.RequiredArgsConstructor;\n" +
                "@RequiredArgsConstructor\n" +
                "public class TestClass {\n" +
                "    private final String required;\n" +
                "    private String optional;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.hasRequiredArgsConstructor, is(true));
        var params = metadata.getConstructorParameterNames();
        assertThat(params.size(), is(1));
        assertThat(params.get(0), is("required"));
    }

    /**
     * Test @Builder annotation.
     */
    @Test
    public void testBuilderAnnotation() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Builder;\n" +
                "@Builder\n" +
                "public class TestClass {\n" +
                "    private String value;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);

        assertThat(metadata.hasBuilder, is(true));
    }

    /**
     * Test that final fields don't get setters.
     */
    @Test
    public void testFinalFieldsExcludedFromSetters() {
        var classTree = compiler.compile(
                "package test;\n" +
                "import lombok.Data;\n" +
                "@Data\n" +
                "public class TestClass {\n" +
                "    private final String immutable;\n" +
                "    private String mutable;\n" +
                "}");

        var metadata = LombokSupport.analyze(classTree);
        var setterNames = metadata.getGeneratedSetterNames();

        assertThat(setterNames, hasItem("setMutable"));
        assertThat(setterNames, not(hasItem("setImmutable")));
    }
}
