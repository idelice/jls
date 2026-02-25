package org.javacs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LombokHandlerTest {
    @Test
    public void skipsJdkTypesForLombokLookup() {
        assertTrue(LombokHandler.shouldSkipLombokLookup("java.math.BigDecimal"));
        assertTrue(LombokHandler.shouldSkipLombokLookup("javax.validation.Valid"));
        assertTrue(LombokHandler.shouldSkipLombokLookup("jdk.internal.Foo"));
        assertTrue(LombokHandler.shouldSkipLombokLookup("sun.misc.Unsafe"));
    }

    @Test
    public void doesNotSkipProjectTypesForLombokLookup() {
        assertFalse(LombokHandler.shouldSkipLombokLookup("com.example.demo.models.Foo"));
        assertFalse(LombokHandler.shouldSkipLombokLookup("org.javacs.example.Slf4jRecord"));
    }

    @Test
    public void shortCircuitsNonProjectClassTreeLookup() {
        assertTrue(LombokHandler.shouldShortCircuitClassTreeLookup("java.util.Map"));
        assertTrue(LombokHandler.shouldShortCircuitClassTreeLookup("javax.validation.Valid"));
        assertTrue(LombokHandler.shouldShortCircuitClassTreeLookup("jdk.internal.misc.Unsafe"));
        assertTrue(LombokHandler.shouldShortCircuitClassTreeLookup("sun.misc.Unsafe"));
        assertFalse(LombokHandler.shouldShortCircuitClassTreeLookup("com.example.demo.models.ThisPoj"));
        assertFalse(LombokHandler.shouldShortCircuitClassTreeLookup("org.javacs.example.Slf4jRecord"));
    }
}
