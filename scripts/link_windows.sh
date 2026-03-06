#!/usr/bin/env bash
# Create self-contained copy of java in dist/windows

set -e

# Set env variables to build with mac toolchain but windows target
# Decide which JDK to use
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  VERSION=$("$JAVA_HOME/bin/java" -version 2>&1 | awk -F[\".] '/version/ {print $2}')
  if [ "$VERSION" -ge 21 ]; then
    JDK="$JAVA_HOME"
  else
    JDK="./jdks/linux/jdk-21"
  fi
else
  JDK="./jdks/linux/jdk-21"
fi

# Build in dist/windows
rm -rf dist/windows
jlink \
  --module-path $JDK/jmods \
  --add-modules java.base,java.compiler,java.logging,java.sql,java.xml,jdk.compiler,jdk.jdi,jdk.unsupported,jdk.zipfs \
  --output dist/windows \
  --no-header-files \
  --no-man-pages \
  --compress 2

