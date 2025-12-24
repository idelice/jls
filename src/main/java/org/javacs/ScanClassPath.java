package org.javacs;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import org.javacs.guava.ClassPath;

class ScanClassPath {
    private static final Path CACHE_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "jls-cache");
    private static final Gson GSON = new Gson();

    static {
        try {
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

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
        var cacheFile = CACHE_DIR.resolve("jdk-classes.json");
        if (Files.exists(cacheFile)) {
            try (var reader = new InputStreamReader(Files.newInputStream(cacheFile), StandardCharsets.UTF_8)) {
                return GSON.fromJson(reader, new TypeToken<Set<String>>() {}.getType());
            } catch (IOException e) {
                LOG.warning("Failed to read JDK cache: " + e.getMessage());
            }
        }

        LOG.info("Searching for top-level classes in the JDK");

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

        LOG.info(String.format("Found %d classes in the java platform", classes.size()));

        try (var writer = new OutputStreamWriter(Files.newOutputStream(cacheFile), StandardCharsets.UTF_8)) {
            GSON.toJson(classes, writer);
        } catch (IOException e) {
            LOG.warning("Failed to write JDK cache: " + e.getMessage());
        }

        return classes;
    }

    static Set<String> classPathTopLevelClasses(Set<Path> classPath) {
        var sb = new StringBuilder();
        for (var p : classPath) {
            sb.append(p.toString()).append(File.pathSeparator);
        }
        var hash = md5(sb.toString());
        var cacheFile = CACHE_DIR.resolve("classpath-" + hash + ".json");
        if (Files.exists(cacheFile)) {
            try (var reader = new InputStreamReader(Files.newInputStream(cacheFile), StandardCharsets.UTF_8)) {
                return GSON.fromJson(reader, new TypeToken<Set<String>>() {}.getType());
            } catch (IOException e) {
                LOG.warning("Failed to read classpath cache: " + e.getMessage());
            }
        }

        LOG.info(String.format("Searching for top-level classes in %d classpath locations", classPath.size()));

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

        LOG.info(String.format("Found %d classes in classpath", classes.size()));

        try (var writer = new OutputStreamWriter(Files.newOutputStream(cacheFile), StandardCharsets.UTF_8)) {
            GSON.toJson(classes, writer);
        } catch (IOException e) {
            LOG.warning("Failed to write classpath cache: " + e.getMessage());
        }

        return classes;
    }

    private static String md5(String input) {
        try {
            var md = MessageDigest.getInstance("MD5");
            var bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (var b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
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
