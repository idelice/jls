package org.javacs.navigation;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import org.junit.Test;

public class NavigationSymbolSupportTest {
    @Test
    public void canonicalTypeUsesSharedBoxedNameMapping() {
        assertThat(NavigationSymbolSupport.canonicalType("int[]"), is("java.lang.Integer"));
        assertThat(NavigationSymbolSupport.canonicalType("? extends java.lang.Long"), is("java.lang.Long"));
    }

    @Test
    public void accessorNamesFollowSharedFieldAliasRules() {
        assertThat(
                new ArrayList<>(NavigationSymbolSupport.accessorNames("enabled")),
                contains("getEnabled", "isEnabled", "setEnabled"));
    }

    @Test
    public void simpleTreeNameDropsTypeArguments() {
        assertThat(NavigationSymbolSupport.simpleTreeName("java.util.Map.Entry<java.lang.String, Foo>"), is("Entry"));
    }
}
