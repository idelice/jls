@echo off
set JLINK_VM_OPTIONS=^
--add-modules jdk.jdeps ^
--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED ^
--add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED ^
--add-opens jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED ^
--add-opens jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED ^
--add-opens jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED ^
--add-opens jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED ^
--add-opens jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED ^
--add-opens jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED ^
--add-opens jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED ^
--add-opens jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED ^
--add-opens jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED ^
--add-opens jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED
set CLASSPATH_OPTIONS=-classpath "%~dp0classpath/*"
set JAVA_BIN=%~dp0windows\bin\java.exe
set LOGGING_CONFIG=%~dp0windows\conf\logging.properties
if defined JLS_JVM_OPTS (
    "%JAVA_BIN%" %JLINK_VM_OPTIONS% %JLS_JVM_OPTS% -Djava.util.logging.config.file="%LOGGING_CONFIG%" %CLASSPATH_OPTIONS% %*
) else (
    "%JAVA_BIN%" %JLINK_VM_OPTIONS% -Xmx2g -Xms512m -XX:MaxHeapFreeRatio=50 -XX:MinHeapFreeRatio=20 -XX:+UseStringDeduplication -Djava.util.logging.config.file="%LOGGING_CONFIG%" %CLASSPATH_OPTIONS% %*
)
