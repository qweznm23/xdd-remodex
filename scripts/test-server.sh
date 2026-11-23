#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
node_bin=${NODE_BIN:-node}
if [[ -z ${NODE_BIN:-} && -x "$project_dir/.local/toolchains/node/bin/node" ]]; then node_bin="$project_dir/.local/toolchains/node/bin/node"; fi
python_bin=${PYTHON_BIN:-/usr/bin/python3}
cd "$project_dir/phodex-bridge"
"$node_bin" --test test/*.test.js
"$python_bin" test/auth-profiles-test.py
cd "$project_dir/relay"
"$node_bin" --test ./*.test.js
