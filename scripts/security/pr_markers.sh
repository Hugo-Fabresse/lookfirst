#!/usr/bin/env bash
# usage: pr_markers.sh <base-sha> <head-sha>
# Warn (job summary) when a PR touches scanner config / ignore files or adds inline suppression
# markers: they could hide findings, so the reviewer should look at them.
set -u
base="$1"; head="$2"
out="${GITHUB_STEP_SUMMARY:-/dev/null}"

files=$(git diff --name-only "$base" "$head" | grep -E '(^|/)(\.gitleaks[^/]*|\.semgrepignore|\.semgrep[^/]*|osv-scanner\.toml)$' || true)
marks=$(git diff -U0 "$base" "$head" | grep -E '^\+' | grep -v '^+++' \
  | grep -cE 'nosemgrep|gitleaks:allow' || true)

if [ -n "$files" ] || [ "${marks:-0}" -gt 0 ]; then
  {
    echo "### Attention : la PR peut masquer des findings"
    [ -n "$files" ] && printf 'Fichiers de config/ignore modifies (gitleaks ne les lit pas ; osv-scanner lit osv-scanner.toml : une entree IgnoredVulns masque une vulnerabilite) :\n%s\n' \
      "$(echo "$files" | tr -d '`<>@' | sed 's/^/- `/; s/$/`/')"
    [ "${marks:-0}" -gt 0 ] && echo "- ${marks} ligne(s) ajoutee(s) avec \`nosemgrep\` / \`gitleaks:allow\`"
    echo
  } >> "$out"
fi
