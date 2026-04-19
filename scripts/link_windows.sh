#!/usr/bin/env bash
# Create self-contained copy of java in dist/windows

set -e

# Set env variables to build with mac toolchain but windows target
JAVA_HOME="./jdks/windows/jdk-25"
JLINK_BIN="${JLINK_BIN:-jlink}"

# Build in dist/windows
rm -rf dist/windows
"$JLINK_BIN" \
  --module-path $JAVA_HOME/jmods \
  --add-modules java.base,java.compiler,java.logging,java.sql,java.xml,jdk.compiler,jdk.jdeps,jdk.jdi,jdk.unsupported,jdk.zipfs \
  --output dist/windows \
  --no-header-files \
  --no-man-pages \
  --compress 2

cp ./scripts/logging.properties dist/windows/conf/logging.properties
