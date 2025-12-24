#!/usr/bin/env bash
set -euo pipefail

config_dir="${HOME}/.config/jls"
config_file="${config_dir}/logging.properties"
level="FINE"

usage() {
  cat <<'EOF'
Usage: write_logging_config.sh [--level LEVEL]

Options:
  --level LEVEL   Set log level (INFO, FINE, FINER, FINEST, CONFIG, WARNING, SEVERE, OFF)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --level)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for --level" >&2
        usage
        exit 2
      fi
      level="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

mkdir -p "${config_dir}"

cat > "${config_file}" <<EOF
# Default level for everything
.level=INFO

# Enable debug timing logs from the main logger
main.level=${level}

# Console handler should emit ${level} as well
java.util.logging.ConsoleHandler.level=${level}
java.util.logging.ConsoleHandler.formatter=org.javacs.LogFormat
handlers=java.util.logging.ConsoleHandler
EOF

echo "Wrote ${config_file} (main.level=${level})"
