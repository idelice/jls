#!/usr/bin/env bash

set -e

JDK_VERSION="25"
JDK_VERSION_FULL="25.0.2"

runtime_needs_refresh() {
    local release_file="$1"
    if [ ! -e "$release_file" ]; then
        return 0
    fi
    ! grep -q 'jdk.jdeps' "$release_file" || ! grep -q "JAVA_VERSION=\"$JDK_VERSION\"" "$release_file"
}

build_java_home() {
    case "$(uname -s)" in
        Darwin)
            echo "./jdks/mac/jdk-$JDK_VERSION/Contents/Home"
            ;;
        Linux)
            echo "./jdks/linux/jdk-$JDK_VERSION"
            ;;
        *)
            echo ""
            ;;
    esac
}

HOST_JAVA_HOME=""
HOST_JLINK=""

# Build standalone java
if [ ! -e "jdks/linux/jdk-$JDK_VERSION" ]; then
    ./scripts/download_linux_jdk.sh
fi
if [ ! -e "jdks/windows/jdk-$JDK_VERSION" ]; then
    ./scripts/download_windows_jdk.sh
fi
if [ ! -e "jdks/mac/jdk-$JDK_VERSION" ]; then
    ./scripts/download_mac_jdk.sh
fi
HOST_JAVA_HOME="$(build_java_home)"
HOST_JLINK="$HOST_JAVA_HOME/bin/jlink"
if [ ! -x "$HOST_JLINK" ]; then
    echo "Missing host jlink at $HOST_JLINK" >&2
    exit 1
fi
if [ ! -e dist/linux/bin/java ] || runtime_needs_refresh dist/linux/release; then
    JLINK_BIN="$HOST_JLINK" ./scripts/link_linux.sh
fi
if [ ! -e dist/windows/bin/java.exe ] || runtime_needs_refresh dist/windows/release; then
    JLINK_BIN="$HOST_JLINK" ./scripts/link_windows.sh
fi
if [ ! -e dist/mac/bin/java ] || runtime_needs_refresh dist/mac/release; then
    ./scripts/link_mac.sh
fi

# Compile sources
if [ ! -e src/main/java/com/google/devtools/build/lib/analysis/AnalysisProtos.java ]; then
    ./scripts/gen_proto.sh
fi

rm -f dist/classpath/lombok*.jar

JAVA_HOME="$HOST_JAVA_HOME"
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "Unsupported build host or missing JDK at $JAVA_HOME" >&2
    exit 1
fi

JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" mvn package -DskipTests

echo "JLS build completed with Java $JDK_VERSION_FULL"
