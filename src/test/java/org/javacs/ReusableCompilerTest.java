package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.util.Log;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.Test;

public class ReusableCompilerTest {
    @Test
    public void clearResetsAnnotationProcessingState() throws Exception {
        var context = new ReusableCompiler.ReusableContext(List.of("-proc:full"));
        var compiler = (ReusableCompiler.ReusableContext.ReusableJavaCompiler) JavaCompiler.instance(context);

        setField(JavaCompiler.class, compiler, "processAnnotations", true);
        setField(
                JavaCompiler.class,
                compiler,
                "deferredDiagnosticHandler",
                new Log.DeferredDiagnosticHandler(Log.instance(context)));
        setOptionalField(JavaCompiler.class, compiler, "annotationProcessingOccurred", true);
        setOptionalField(JavaCompiler.class, compiler, "explicitAnnotationProcessingRequested", true);

        compiler.clear();

        assertThat((Boolean) getField(JavaCompiler.class, compiler, "processAnnotations"), is(false));
        assertThat(getField(JavaCompiler.class, compiler, "deferredDiagnosticHandler"), is(nullValue()));
        assertOptionalBooleanField(JavaCompiler.class, compiler, "annotationProcessingOccurred", false);
        assertOptionalBooleanField(JavaCompiler.class, compiler, "explicitAnnotationProcessingRequested", false);
    }

    private static void assertOptionalBooleanField(
            Class<?> owner, Object target, String fieldName, boolean expected) throws Exception {
        try {
            assertThat((Boolean) getField(owner, target, fieldName), is(expected));
        } catch (NoSuchFieldException ignored) {
            // Field names can vary across JDK versions.
        }
    }

    private static void setOptionalField(Class<?> owner, Object target, String fieldName, Object value)
            throws Exception {
        try {
            setField(owner, target, fieldName, value);
        } catch (NoSuchFieldException ignored) {
            // Field names can vary across JDK versions.
        }
    }

    private static Object getField(Class<?> owner, Object target, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Class<?> owner, Object target, String fieldName, Object value) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
