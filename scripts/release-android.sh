#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
mkdir -p "$project_dir/.local/logs"
bash "$project_dir/scripts/build-android.sh" :app:testReleaseUnitTest :app:lintVitalRelease :app:assembleRelease "$@" \
  2>&1 | tee "$project_dir/.local/logs/android-release.log"
