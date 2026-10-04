#!/usr/bin/env bash
# The plugin's upstream DSL stays source compatible: against the upstream branch, the dsl
# directory may gain declarations and may not lose or change public ones.
# Usage: check-plugin-dsl-additive.sh [upstream ref, default origin/jb-main]
# The plugin has no binary-compatibility dump, so this compares declarations in the diff.
set -euo pipefail
base="${1:-origin/jb-main}"
dir=gradle-plugins/compose/src/main/kotlin/org/jetbrains/compose/desktop/application/dsl
removed=$(git diff "$base"...HEAD -U0 -- "$dir" | grep -E '^-[^-]' \
    | grep -E '^-\s*((public|abstract|open|override|internal|data|sealed|enum|annotation)\s+)*(val|var|fun|class|interface|object)\s' \
    | grep -vE '^-\s*(private|internal)\s' || true)
if [[ -n "$removed" ]]; then
    echo "FAIL: public DSL declarations removed or changed against $base:" >&2
    echo "$removed" >&2
    exit 1
fi
echo "upstream DSL additive against $base"
