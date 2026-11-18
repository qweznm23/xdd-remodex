#!/usr/bin/env bash
set -euo pipefail
script_file=$(readlink -f -- "${BASH_SOURCE[0]}")
project_dir=$(cd -- "$(dirname -- "$script_file")/.." && pwd -P)
if [[ -f "$project_dir/.local/config/build.env" ]]; then
  set -a
  source "$project_dir/.local/config/build.env"
  set +a
fi
if [[ ${XDD_REMODEX_BUILD_LOCK_HELD:-0} != 1 ]]; then
  exec flock /var/lock/android-toolchain-validation.lock env XDD_REMODEX_BUILD_LOCK_HELD=1 bash "$0" "$@"
fi
: "${JAVA_HOME:?Configure JAVA_HOME in .local/config/build.env}"
: "${ANDROID_HOME:?Configure ANDROID_HOME in .local/config/build.env}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$project_dir/.local/cache/gradle}"
unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy
proxy_args=()
if [[ -n ${GRADLE_PROXY_HOST:-} ]]; then
  : "${GRADLE_PROXY_PORT:?Configure GRADLE_PROXY_PORT}"
  proxy_args+=("-Dhttp.proxyHost=$GRADLE_PROXY_HOST" "-Dhttp.proxyPort=$GRADLE_PROXY_PORT"
    "-Dhttps.proxyHost=$GRADLE_PROXY_HOST" "-Dhttps.proxyPort=$GRADLE_PROXY_PORT"
    '-Dhttp.nonProxyHosts=localhost|127.*|192.168.*')
fi
cd "$project_dir/android"
exec bash ./gradlew "${proxy_args[@]}" "$@"
