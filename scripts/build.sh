#!/usr/bin/env bash

set -e

# Detect usable system Java
USE_SYSTEM_JAVA=false

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  VERSION=$("$JAVA_HOME/bin/java" -version 2>&1 | awk -F[\".] '/version/ {print $2}')
  if [ "$VERSION" -ge 21 ]; then
    USE_SYSTEM_JAVA=true
  fi
fi

OS="$(uname -s)"

# Download JDK only if needed
if [ "$USE_SYSTEM_JAVA" = false ]; then
  case "$OS" in
  Linux*)
    if [ ! -e jdks/linux/jdk-21 ]; then
      ./scripts/download_linux_jdk.sh
    fi
    ;;
  Darwin*)
    if [ ! -e jdks/mac/jdk-21 ]; then
      ./scripts/download_mac_jdk.sh
    fi
    ;;
  MINGW* | MSYS* | CYGWIN*)
    if [ ! -e jdks/windows/jdk-21 ]; then
      ./scripts/download_windows_jdk.sh
    fi
    ;;
  esac
fi

# Run only the correct link script
case "$OS" in
Linux*)
  if [ ! -e dist/linux/bin/java ]; then
    ./scripts/link_linux.sh
  fi
  ;;
Darwin*)
  if [ ! -e dist/mac/bin/java ]; then
    ./scripts/link_mac.sh
  fi
  ;;
MINGW* | MSYS* | CYGWIN*)
  if [ ! -e dist/windows/bin/java.exe ]; then
    ./scripts/link_windows.sh
  fi
  ;;
esac

# Compile sources
if [ ! -e src/main/java/com/google/devtools/build/lib/analysis/AnalysisProtos.java ]; then
  ./scripts/gen_proto.sh
fi

mvn package -DskipTests

echo 'Reload VSCode to update extension'
