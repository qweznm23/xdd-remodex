#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
if [[ -f "$project_dir/.local/config/build.env" ]]; then
  set -a
  source "$project_dir/.local/config/build.env"
  set +a
fi
exec /usr/bin/python3 "$project_dir/scripts/publish-android.py" "$@"
