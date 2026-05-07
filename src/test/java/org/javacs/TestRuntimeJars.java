package org.javacs;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assert;

final class TestRuntimeJars {
    private TestRuntimeJars() {}

    static Path lombokJar() {
        return runtimeJarPath("lombok.Data");
    }

    static Path runtimeJarPath(String className) {
        try {
            var type = Class.forName(className);
            var codeSource = type.getProtectionDomain().getCodeSource();
            Assert.assertNotNull("Missing code source for " + className, codeSource);
            return Paths.get(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new RuntimeException("Unable to locate runtime jar for " + className, e);
        }
    }
}
