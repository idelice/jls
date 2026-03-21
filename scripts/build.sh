#!/usr/bin/env bash

set -e

runtime_missing_module() {
    local release_file="$1"
    if [ ! -e "$release_file" ]; then
        return 0
    fi
    ! grep -q 'jdk.jdeps' "$release_file"
}

# Needed once
# if [ ! -e node_modules ]; then
#     npm install
# fi

# Build standalone java
if [ ! -e jdks/linux/jdk-21 ]; then
    ./scripts/download_linux_jdk.sh
fi
if [ ! -e jdks/windows/jdk-21 ]; then
    ./scripts/download_windows_jdk.sh
fi
if [ ! -e dist/linux/bin/java ] || runtime_missing_module dist/linux/release; then
    ./scripts/link_linux.sh
fi
if [ ! -e dist/windows/bin/java.exe ] || runtime_missing_module dist/windows/release; then
    ./scripts/link_windows.sh
fi
if [ ! -e dist/mac/bin/java ] || runtime_missing_module dist/mac/release; then
    ./scripts/link_mac.sh
fi

# Compile sources
if [ ! -e src/main/java/com/google/devtools/build/lib/analysis/AnalysisProtos.java ]; then
    ./scripts/gen_proto.sh
fi

mvn package -DskipTests

# Build vsix
# npm run-script vscode:build
#
# code --install-extension build.vsix --force

echo 'Reload VSCode to update extension'
