#!/usr/bin/env bash

set -e

# Compile sources
if [ ! -e src/main/java/com/google/devtools/build/lib/analysis/AnalysisProtos.java ]; then
    ./scripts/gen_proto.sh
fi

mkdir -p dist/classpath
rm -f dist/classpath/*.jar

mvn package -DskipTests
