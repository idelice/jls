package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

public class CompileBatchTest {
    @Test
    public void optionsWithoutApFiltersSplitProcessorArgs() {
        var extraArgs = new LinkedHashSet<String>();
        extraArgs.add("-processor");
        extraArgs.add("com.example.Processor");
        extraArgs.add("-processorpath");
        extraArgs.add("/tmp/processors");
        extraArgs.add("--processor-path");
        extraArgs.add("/tmp/more-processors");
        extraArgs.add("-Xlint:rawtypes");

        var options = CompileBatch.optionsWithoutAP(Set.of(), Set.of(), extraArgs);

        assertThat(options, hasItems("-Xlint:rawtypes"));
        assertThat(options, not(hasItems("-processor", "com.example.Processor")));
        assertThat(options, not(hasItems("-processorpath", "/tmp/processors")));
        assertThat(options, not(hasItems("--processor-path", "/tmp/more-processors")));
    }

    @Test
    public void optionsWithoutApFiltersInlineProcessorArgs() {
        var extraArgs =
                new LinkedHashSet<String>(
                        Set.of(
                                "-processor=com.example.Processor",
                                "-processorpath=/tmp/processors",
                                "--processor-path=/tmp/more-processors",
                                "-Xlint:rawtypes"));

        var options = CompileBatch.optionsWithoutAP(Set.of(), Set.of(), extraArgs);

        assertThat(options, hasItems("-Xlint:rawtypes"));
        assertThat(options, not(hasItems("-processor=com.example.Processor")));
        assertThat(options, not(hasItems("-processorpath=/tmp/processors")));
        assertThat(options, not(hasItems("--processor-path=/tmp/more-processors")));
    }
}
