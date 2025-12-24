#!/usr/bin/env bash
set -euo pipefail

OUT_PATH="${1:-${JAVA_LSP_RUNTIMES_OUT:-$HOME/.config/jls/runtimes.json}}"
SDKMAN_DIR="${SDKMAN_CANDIDATES_DIR:-$HOME/.sdkman/candidates/java}"
ASDF_DIR="${ASDF_DATA_DIR:-$HOME/.asdf}/installs/java"
MAC_JDK_DIR="/Library/Java/JavaVirtualMachines"

mkdir -p "$(dirname "$OUT_PATH")"

SDKMAN_DIR="$SDKMAN_DIR" ASDF_DIR="$ASDF_DIR" MAC_JDK_DIR="$MAC_JDK_DIR" \
python3 - "$OUT_PATH" <<'PY'
import json
import os
import pathlib
import re
import sys

out_path = pathlib.Path(sys.argv[1]).expanduser()
sdkman_dir = pathlib.Path(os.environ.get("SDKMAN_DIR", ""))
asdf_dir = pathlib.Path(os.environ.get("ASDF_DIR", ""))
mac_jdk_dir = pathlib.Path(os.environ.get("MAC_JDK_DIR", ""))

def parse_release(value):
    if not value:
        return None
    s = value.strip()
    m = re.match(r"1\.(\d+)", s)
    if m:
        return int(m.group(1))
    m = re.search(r"(\d+)", s)
    if not m:
        return None
    return int(m.group(1))

def add_runtime(store, version, path):
    if version is None or version in store:
        return
    java = pathlib.Path(path) / "bin" / "java"
    if java.is_file():
        store[version] = str(path)

def scan_sdkman(store):
    if not sdkman_dir.is_dir():
        return
    for child in sorted(sdkman_dir.iterdir()):
        if child.is_dir():
            version = parse_release(child.name)
            add_runtime(store, version, child)

def scan_asdf(store):
    if not asdf_dir.is_dir():
        return
    for child in sorted(asdf_dir.iterdir()):
        if child.is_dir():
            version = parse_release(child.name)
            add_runtime(store, version, child)

def scan_mac(store):
    if not mac_jdk_dir.is_dir():
        return
    for child in sorted(mac_jdk_dir.iterdir()):
        home = child / "Contents" / "Home"
        if home.is_dir():
            version = parse_release(child.name)
            add_runtime(store, version, home)

store = {}
scan_sdkman(store)
scan_asdf(store)
scan_mac(store)

runtimes = [{"version": v, "path": store[v]} for v in sorted(store.keys())]
out_path.write_text(json.dumps(runtimes, indent=2) + "\n")
print(f"Wrote {len(runtimes)} runtimes to {out_path}")
PY
