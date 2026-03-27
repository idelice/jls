package org.javacs;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import org.javacs.guava.ClassPath;

class ScanClassPath {

    // TODO delete this and implement findPublicTypeDeclarationInJdk some other way
    /** All exported modules that are present in JDK 10 or 11 */
    static String[] JDK_MODULES = {
        "java.activation",
        "java.base",
        "java.compiler",
        "java.corba",
        "java.datatransfer",
        "java.desktop",
        "java.instrument",
        "java.jnlp",
        "java.logging",
        "java.management",
        "java.management.rmi",
        "java.naming",
        "java.net.http",
        "java.prefs",
        "java.rmi",
        "java.scripting",
        "java.se",
        "java.se.ee",
        "java.security.jgss",
        "java.security.sasl",
        "java.smartcardio",
        "java.sql",
        "java.sql.rowset",
        "java.transaction",
        "java.transaction.xa",
        "java.xml",
        "java.xml.bind",
        "java.xml.crypto",
        "java.xml.ws",
        "java.xml.ws.annotation",
        "javafx.base",
        "javafx.controls",
        "javafx.fxml",
        "javafx.graphics",
        "javafx.media",
        "javafx.swing",
        "javafx.web",
        "jdk.accessibility",
        "jdk.aot",
        "jdk.attach",
        "jdk.charsets",
        "jdk.compiler",
        "jdk.crypto.cryptoki",
        "jdk.crypto.ec",
        "jdk.dynalink",
        "jdk.editpad",
        "jdk.hotspot.agent",
        "jdk.httpserver",
        "jdk.incubator.httpclient",
        "jdk.internal.ed",
        "jdk.internal.jvmstat",
        "jdk.internal.le",
        "jdk.internal.opt",
        "jdk.internal.vm.ci",
        "jdk.internal.vm.compiler",
        "jdk.internal.vm.compiler.management",
        "jdk.jartool",
        "jdk.javadoc",
        "jdk.jcmd",
        "jdk.jconsole",
        "jdk.jdeps",
        "jdk.jdi",
        "jdk.jdwp.agent",
        "jdk.jfr",
        "jdk.jlink",
        "jdk.jshell",
        "jdk.jsobject",
        "jdk.jstatd",
        "jdk.localedata",
        "jdk.management",
        "jdk.management.agent",
        "jdk.management.cmm",
        "jdk.management.jfr",
        "jdk.management.resource",
        "jdk.naming.dns",
        "jdk.naming.rmi",
        "jdk.net",
        "jdk.pack",
        "jdk.packager.services",
        "jdk.rmic",
        "jdk.scripting.nashorn",
        "jdk.scripting.nashorn.shell",
        "jdk.sctp",
        "jdk.security.auth",
        "jdk.security.jgss",
        "jdk.snmp",
        "jdk.unsupported",
        "jdk.unsupported.desktop",
        "jdk.xml.dom",
        "jdk.zipfs",
    };

    static Set<String> jdkTopLevelClasses() {
        LOG.info("Searching for top-level classes in the JDK");
        var started = Instant.now();

        var classes = new HashSet<String>();
        var fs = FileSystems.getFileSystem(URI.create("jrt:/"));
        for (var m : JDK_MODULES) {
            var moduleRoot = fs.getPath(String.format("/modules/%s/", m));
            try (var stream = Files.walk(moduleRoot)) {
                var it = stream.iterator();
                while (it.hasNext()) {
                    var classFile = it.next();
                    var relative = moduleRoot.relativize(classFile).toString();
                    if (relative.endsWith(".class") && !relative.contains("$")) {
                        var trim = relative.substring(0, relative.length() - ".class".length());
                        var qualifiedName = trim.replace(File.separatorChar, '.');
                        classes.add(qualifiedName);
                    }
                }
            } catch (IOException e) {
                // LOG.log(Level.WARNING, "Failed indexing module " + m + "(" + e.getMessage() + ")");
            }
        }

        LOG.info(
                String.format(
                        "[perf] jdk_class_scan modules=%d classes=%d took=%dms",
                        JDK_MODULES.length, classes.size(), Duration.between(started, Instant.now()).toMillis()));

        return classes;
    }

    static Set<String> classPathTopLevelClasses(Set<Path> classPath) {
        LOG.info(String.format("Searching for top-level classes in %d classpath locations", classPath.size()));
        var started = Instant.now();

        var urls = classPath.stream().map(ScanClassPath::toUrl).toArray(URL[]::new);
        var classLoader = new URLClassLoader(urls, null);
        ClassPath scanner;
        try {
            scanner = ClassPath.from(classLoader);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var classes = new HashSet<String>();
        for (var c : scanner.getTopLevelClasses()) {
            classes.add(c.getName());
        }

        LOG.info(
                String.format(
                        "[perf] classpath_scan locations=%d classes=%d took=%dms",
                        classPath.size(), classes.size(), Duration.between(started, Instant.now()).toMillis()));

        return classes;
    }

    private static URL toUrl(Path p) {
        try {
            return p.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
