#!/usr/bin/env bash
# Create self-contained copy of java in dist/mac

set -e

# Decide which JDK to use
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  VERSION=$("$JAVA_HOME/bin/java" -version 2>&1 | awk -F[\".] '/version/ {print $2}')
  if [ "$VERSION" -ge 21 ]; then
    JDK="$JAVA_HOME"
  else
    JDK="./jdks/mac/jdk-21/Contents/Home"
  fi
else
  JDK="./jdks/mac/jdk-21/Contents/Home"
fi

# Build using jlink
rm -rf dist/mac
"$JDK/bin/jlink" \
  --module-path "$JDK/jmods" \
  --add-modules java.base,java.compiler,java.logging,java.sql,java.xml,jdk.compiler,jdk.jdi,jdk.unsupported,jdk.zipfs \
  --output dist/mac \
  --no-header-files \
  --no-man-pages \
  --compress 2
