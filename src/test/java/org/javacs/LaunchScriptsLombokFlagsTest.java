package org.javacs;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class LaunchScriptsLombokFlagsTest {
    private static final List<String> REQUIRED_JAVAC_PACKAGES =
            List.of(
                    "com.sun.tools.javac.api",
                    "com.sun.tools.javac.code",
                    "com.sun.tools.javac.comp",
                    "com.sun.tools.javac.file",
                    "com.sun.tools.javac.jvm",
                    "com.sun.tools.javac.main",
                    "com.sun.tools.javac.model",
                    "com.sun.tools.javac.parser",
                    "com.sun.tools.javac.processing",
                    "com.sun.tools.javac.tree",
                    "com.sun.tools.javac.util");

    @Test
    public void unixLaunchScriptsExposeRequiredJavacInternals() throws IOException {
        assertContainsRequiredFlags(Path.of("dist/launch_mac.sh"));
        assertContainsRequiredFlags(Path.of("dist/launch_linux.sh"));
        assertContainsRequiredFlags(Path.of("dist/launch_windows.sh"));
    }

    @Test
    public void windowsCmdLaunchScriptExposesRequiredJavacInternals() throws IOException {
        assertContainsRequiredFlags(Path.of("dist/launch_windows.cmd"));
    }

    private void assertContainsRequiredFlags(Path script) throws IOException {
        var content = Files.readString(script);
        for (var javacPackage : REQUIRED_JAVAC_PACKAGES) {
            var moduleAccess = "jdk.compiler/" + javacPackage + "=ALL-UNNAMED";
            assertThat(content, containsString("--add-exports " + moduleAccess));
            assertThat(content, containsString("--add-opens " + moduleAccess));
        }
    }
}
