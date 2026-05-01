#!/usr/bin/env bash
# Create self-contained copy of java in dist/linux

set -e

# Set env variables to build with mac toolchain but linux target
JAVA_HOME="./jdks/linux/jdk-25"
JLINK_BIN="${JLINK_BIN:-jlink}"

# Build in dist/linux
rm -rf dist/linux
"$JLINK_BIN" \
  --module-path $JAVA_HOME/jmods \
  --add-modules java.base,java.compiler,java.logging,java.management,java.sql,java.xml,jdk.compiler,jdk.jdeps,jdk.jdi,jdk.unsupported,jdk.zipfs \
  --output dist/linux \
  --no-header-files \
  --no-man-pages \
  --compress 2

cp ./scripts/logging.properties dist/linux/conf/logging.properties
