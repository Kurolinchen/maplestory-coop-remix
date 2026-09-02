#!/usr/bin/env bash
# Build the German handbook PDF from the checked-in HTML/CSS/JSON sources and
# copy it to the user desktop.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

pass_args=()
for arg in "$@"; do
  case "$arg" in
    --no-pdf|--no-desktop) pass_args+=("$arg") ;;
    -h|--help)
      printf 'Usage: ops/build-handbook.sh [--no-pdf] [--no-desktop]\n'
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$arg" >&2
      exit 1
      ;;
  esac
done

if [[ ! -f docs/handbook/commands.json ]]; then
  python3 tools/handbook/generate-commands.py
fi

python3 tools/handbook/build-handbook.py "${pass_args[@]}"
