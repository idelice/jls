#!/bin/sh
JLINK_VM_OPTIONS="\
--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
DIR=`dirname $0`
CLASSPATH_OPTIONS="-classpath $DIR/classpath/*"
JAVA_EXECUTABLE="java"
HOST_JAVA_HOME="${JAVA_LSP_HOST_JAVA:-${JAVA_HOME:-}}"
WORKSPACE_ROOT="${JAVA_LSP_WORKSPACE_ROOT:-${PWD}}"
RUNTIMES_FILE="${JAVA_LSP_RUNTIMES_FILE:-${HOME}/.config/jls/runtimes.json}"
RUNTIME_JAVA=""

if [ -n "$WORKSPACE_ROOT" ] && [ -r "$RUNTIMES_FILE" ] && command -v python3 >/dev/null 2>&1; then
  RUNTIME_JAVA="$(
    JAVA_LSP_WORKSPACE_ROOT="$WORKSPACE_ROOT" JAVA_LSP_RUNTIMES_FILE="$RUNTIMES_FILE" python3 - <<'PY'
import json
import os
import pathlib
import re
import sys

def read_file(path):
    try:
        return pathlib.Path(path).read_text()
    except Exception:
        return None

def parse_release_string(value):
    if not value:
        return None
    s = value.strip()
    if s.startswith("1."):
        s = s[2:]
    s = re.split(r"[^0-9]", s, 1)[0]
    if not s:
        return None
    try:
        return int(s)
    except ValueError:
        return None

def detect_release(root):
    root = pathlib.Path(root)
    java_version = read_file(root / ".java-version")
    if java_version:
        r = parse_release_string(java_version.splitlines()[0] if java_version.splitlines() else java_version)
        if r:
            return r
    tool_versions = read_file(root / ".tool-versions")
    if tool_versions:
        for line in tool_versions.splitlines():
            line = line.strip()
            if line.startswith("java "):
                parts = line.split()
                if len(parts) >= 2:
                    r = parse_release_string(parts[1])
                    if r:
                        return r
    pom = read_file(root / "pom.xml")
    if pom:
        for tag in ("maven.compiler.release", "maven.compiler.source", "java.version"):
            m = re.search(rf"<{tag}>\s*([^<]+)\s*</{tag}>", pom)
            if m:
                r = parse_release_string(m.group(1))
                if r:
                    return r
    for gradle_name in ("build.gradle", "build.gradle.kts"):
        gradle = read_file(root / gradle_name)
        if not gradle:
            continue
        for key in ("sourceCompatibility", "targetCompatibility"):
            m = re.search(rf"{key}\s*=\s*(\"([^\"]+)\"|'([^']+)'|([A-Za-z0-9_\\.]+))", gradle)
            if m:
                v = m.group(2) or m.group(3) or m.group(4)
                r = parse_release_string(v)
                if r:
                    return r
    return None

root = os.environ.get("JAVA_LSP_WORKSPACE_ROOT")
config = os.environ.get("JAVA_LSP_RUNTIMES_FILE")
if not root or not config:
    sys.exit(0)
release = detect_release(root)
if not release:
    sys.exit(0)
cfg = read_file(config)
if not cfg:
    sys.exit(0)
try:
    runtimes = json.loads(cfg)
except Exception:
    sys.exit(0)

for rt in runtimes:
    if not isinstance(rt, dict):
        continue
    if str(rt.get("version")) == str(release):
        path = rt.get("path")
        if not path:
            continue
        java = pathlib.Path(path) / "bin" / "java"
        if java.is_file():
            print(str(java))
            sys.exit(0)
sys.exit(0)
PY
  )"
fi

if [ -n "$RUNTIME_JAVA" ]; then
  JAVA_EXECUTABLE="$RUNTIME_JAVA"
elif [ -n "$HOST_JAVA_HOME" ] && [ -x "$HOST_JAVA_HOME/bin/java" ]; then
  JAVA_EXECUTABLE="$HOST_JAVA_HOME/bin/java"
fi

exec "$JAVA_EXECUTABLE" $JLINK_VM_OPTIONS $CLASSPATH_OPTIONS "$@"
