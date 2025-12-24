#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_CONFIG="${JAVA_LSP_LOGGING_CONFIG:-$HOME/.config/jls/logging.properties}"
WORKSPACE_ROOT="${JAVA_LSP_WORKSPACE_ROOT:-$PWD}"

if [ -f "$LOG_CONFIG" ]; then
  exec "$ROOT_DIR/dist/lang_server_mac.sh" \
    "-Djava.util.logging.config.file=$LOG_CONFIG" \
    "$@"
else
  echo "Logging config not found at $LOG_CONFIG; starting without it." >&2
  exec "$ROOT_DIR/dist/lang_server_mac.sh" "$@"
fi
