@echo off
set JLINK_VM_OPTIONS=^
--add-modules jdk.jdeps ^
--add-exports jdk.jdeps/com.sun.tools.classfile=ALL-UNNAMED ^
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
java %JLINK_VM_OPTIONS% %CLASSPATH_OPTIONS% %*
