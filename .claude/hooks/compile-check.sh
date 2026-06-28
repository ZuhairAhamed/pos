#!/usr/bin/env bash
# PostToolUse hook: when Claude edits a .java file, compile (main + test) so
# compile errors and Spring Modulith boundary breakage surface immediately
# instead of at the end of a long session.
#
# Exit 2 => stderr is fed back to Claude so it can fix the error right away.
set -euo pipefail

input="$(cat)"

# Extract the edited file path from the hook JSON payload.
file_path="$(printf '%s' "$input" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))' 2>/dev/null || true)"

# Only react to Java source changes.
case "$file_path" in
  *.java) ;;
  *) exit 0 ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# This project needs JDK 21 (system default is 17). Resolve it the same way
# the run docs do; fall back to PATH java if the lookup fails.
if JH="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
  export JAVA_HOME="$JH"
fi

# test-compile covers both src/main and src/test in one pass.
if ! out="$(./mvnw -q test-compile 2>&1)"; then
  echo "Compile failed after editing $file_path:" >&2
  echo "$out" | tail -40 >&2
  exit 2
fi

exit 0
