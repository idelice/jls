#!/bin/sh
JLINK_VM_OPTIONS="\
--add-modules jdk.jdeps \
--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED"
DIR=`dirname $0`
JAVA_BIN="$DIR/mac/bin/java"
LOGGING_CONFIG="$DIR/mac/conf/logging.properties"
JLS_JVM_DEFAULT_MEM="-Xmx2g -Xms512m -XX:MaxHeapFreeRatio=50 -XX:MinHeapFreeRatio=20 -XX:+UseStringDeduplication"

# AOT cache: pre-warm JIT profiles for faster startup and peak performance.
AOT_CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/jls"
AOT_CACHE_FILE="$AOT_CACHE_DIR/jls.aot"
AOT_CONF_FILE="$AOT_CACHE_DIR/jls.aotconf"
AOT_OPTS=""
if [ -f "$AOT_CACHE_FILE" ]; then
    AOT_OPTS="-XX:AOTCache=$AOT_CACHE_FILE"
elif [ -f "$AOT_CONF_FILE" ]; then
    "$JAVA_BIN" $JLINK_VM_OPTIONS ${JLS_JVM_OPTS:-$JLS_JVM_DEFAULT_MEM} \
        -XX:AOTMode=create -XX:AOTConfiguration="$AOT_CONF_FILE" \
        -XX:AOTCache="$AOT_CACHE_FILE" -classpath "$DIR/classpath/*" 2>/dev/null
    if [ -f "$AOT_CACHE_FILE" ]; then
        AOT_OPTS="-XX:AOTCache=$AOT_CACHE_FILE"
        rm -f "$AOT_CONF_FILE"
    fi
else
    mkdir -p "$AOT_CACHE_DIR"
    AOT_OPTS="-XX:AOTMode=record -XX:AOTConfiguration=$AOT_CONF_FILE"
fi

exec "$JAVA_BIN" $JLINK_VM_OPTIONS ${JLS_JVM_OPTS:-$JLS_JVM_DEFAULT_MEM} $AOT_OPTS -Djava.util.logging.config.file="$LOGGING_CONFIG" -classpath "$DIR/classpath/*" "$@"
