#!/usr/bin/env bash
set -euo pipefail

calculate_version_code() {
  local version="${1#v}"
  local major minor patch stage beta_number code

  if [[ "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    major=$((10#${BASH_REMATCH[1]}))
    minor=$((10#${BASH_REMATCH[2]}))
    patch=$((10#${BASH_REMATCH[3]}))
    stage=9999
  elif [[ "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)-beta\.?([0-9]+)$ ]]; then
    major=$((10#${BASH_REMATCH[1]}))
    minor=$((10#${BASH_REMATCH[2]}))
    patch=$((10#${BASH_REMATCH[3]}))
    beta_number=$((10#${BASH_REMATCH[4]}))
    if (( beta_number < 1 || beta_number > 9998 )); then
      echo "Beta number must be between 1 and 9998" >&2
      return 1
    fi
    stage=$beta_number
  else
    echo "Unsupported version: $version" >&2
    return 1
  fi

  if (( minor > 99 || patch > 99 )); then
    echo "Minor and patch versions must be between 0 and 99" >&2
    return 1
  fi

  code=$((major * 100000000 + minor * 1000000 + patch * 10000 + stage))
  if (( code < 1 || code > 2100000000 )); then
    echo "Generated Android versionCode is outside the supported range" >&2
    return 1
  fi
  printf '%d\n' "$code"
}

if [[ "${1:-}" == "--self-test" ]]; then
  [[ "$(calculate_version_code 'v0.1.0')" == "1009999" ]]
  [[ "$(calculate_version_code '0.2.0-beta.1')" == "2000001" ]]
  [[ "$(calculate_version_code '0.2.0-beta2')" == "2000002" ]]
  [[ "$(calculate_version_code '0.2.0')" == "2009999" ]]
  [[ "$(calculate_version_code '0.2.1-beta.1')" == "2010001" ]]
  echo "Android versionCode checks passed"
else
  calculate_version_code "${1:-}"
fi
