#!/usr/bin/env bash
# Commit / PR title format: `type(LF-n): summary` (see CLAUDE.md, "Commits and PRs").
#
# usage:
#   check-commit-format.sh --range <base> <head> [--title "PR title"] [--pr-author <login>]
#                                         non-merge commits in base..head (+ title); a PR authored by
#                                         renovate[bot] is exempt
#   check-commit-format.sh --message "subject"                          one subject
#   check-commit-format.sh --commit-msg <file>                          for a commit-msg hook
#   check-commit-format.sh --self-test                                  built-in positive / negative examples
set -u
export LC_ALL=C

TYPES='feat|fix|refactor|test|docs|ci|build|perf|style|chore'
RE="^(${TYPES})\\(LF-[0-9]+\\): [a-z0-9](.*[^. ])?\$"
MAX=72

# Exit 0 if the subject is valid (or exempt: GitHub-generated revert), 1 otherwise.
valid() {
  local s=$1
  [[ $s == 'Revert "'* ]] && return 0
  [ "${#s}" -le "$MAX" ] || return 1
  [[ $s =~ $RE ]]
}

expected() {
  cat <<MSG
Expected format: type(LF-n): summary
  type    : ${TYPES//|/, }
  scope   : the ticket ID, LF-<integer>
  summary : English, starts lowercase, present tense, no trailing period
  subject : at most ${MAX} characters
Example: fix(LF-12): handle empty content
MSG
}

bad=0
report() { # label subject
  printf 'INVALID %s: %s (%d chars)\n' "$1" "$2" "${#2}"
  bad=1
}

self_test() {
  local fail=0 s
  while IFS= read -r s; do
    valid "$s" || { echo "self-test: should pass but fails: $s"; fail=1; }
  done <<'OK'
fix(LF-12): handle empty content
feat(LF-71): add wifi parser
ci(LF-76): enforce the commit and PR title format
chore(LF-1): 2nd pass on deps
fix(LF-1): bump gradle to 8.5
docs(LF-2): add rules in claude.md
Revert "fix(LF-12): handle empty content"
OK
  while IFS= read -r s; do
    valid "$s" && { echo "self-test: should fail but passes: $s"; fail=1; }
  done <<'KO'
Fix stuff
feat(LF-60.5): add wifi parser
fix(LF-12): Handle empty content
fix(LF-12): handle empty content.
fix(LF-12): handle empty content 
fix: handle empty content
fix(deck): handle empty content
fix(LF-x): handle empty content
wip(LF-12): handle empty content
fix(LF-12):handle empty content
fix(LF-12): this subject is far too long to be accepted because it exceeds seventy-two characters
KO
  # Renovate PR exempt, any other author is not.
  local self d2; self=$(realpath "$0"); d2=$(mktemp -d)
  (
    cd "$d2" && git init -q -b main . &&
    git config user.email t@t && git config user.name t && git config commit.gpgsign false &&
    git commit -q --allow-empty -m 'chore(LF-1): base' && git commit -q --allow-empty -m 'chore(deps): update dependency x'
  ) >/dev/null 2>&1
  (cd "$d2" && "$self" --range HEAD~1 HEAD --title 'chore(deps): update x' --pr-author 'renovate[bot]') >/dev/null 2>&1 \
    || { echo 'self-test: Renovate PR should be exempt'; fail=1; }
  (cd "$d2" && "$self" --range HEAD~1 HEAD --title 'chore(deps): update x' --pr-author 'someone') >/dev/null 2>&1 \
    && { echo 'self-test: non-Renovate author must not be exempt'; fail=1; }
  rm -rf "$d2"
  # Merge commits are ignored (parent count > 1), checked on a throw-away repo.
  local d; d=$(mktemp -d)
  (
    cd "$d" && git init -q -b main . &&
    git config user.email t@t && git config user.name t && git config commit.gpgsign false &&
    git commit -q --allow-empty -m 'chore(LF-1): base' &&
    git checkout -q -b side && git commit -q --allow-empty -m 'feat(LF-2): side work' &&
    git checkout -q main && git commit -q --allow-empty -m 'fix(LF-3): main work' &&
    git merge -q --no-ff side -m 'Merge branch side (not in the format)'
  ) >/dev/null 2>&1
  if [ "$(git -C "$d" log --merges --format=%s | wc -l)" -ne 1 ] || git -C "$d" log --no-merges --format=%s | grep -q Merge; then
    echo 'self-test: merge commit handling broken'; fail=1
  fi
  rm -rf "$d"
  [ "$fail" -eq 0 ] && echo 'self-test: ok'
  return "$fail"
}

case ${1:-} in
  --self-test) self_test; exit $? ;;
  --message) valid "${2:-}" || { report subject "${2:-}"; expected; exit 1; }; exit 0 ;;
  --commit-msg)
    s=$(head -n1 "${2:?commit message file}")
    case $s in 'Merge '*|'fixup! '*|'squash! '*) exit 0 ;; esac
    valid "$s" || { report subject "$s"; expected; exit 1; }; exit 0 ;;
  --range)
    base=${2:?base}; head=${3:?head}; shift 3
    git rev-parse --verify --quiet "$base^{commit}" >/dev/null || { echo "unknown base: $base"; exit 2; }
    git rev-parse --verify --quiet "$head^{commit}" >/dev/null || { echo "unknown head: $head"; exit 2; }
    title=; has_title=0; pr_author=
    while [ $# -gt 0 ]; do
      case $1 in
        --title) title=${2-}; has_title=1; shift 2 ;;
        --pr-author) pr_author=${2-}; shift 2 ;;
        *) echo "unknown option: $1"; exit 2 ;;
      esac
    done
    # Renovate titles are `chore(deps): ...` (no ticket). Exempt on the PR author login given by
    # GitHub (cannot be spoofed like a commit author name); every other PR keeps the full rule.
    if [ "$pr_author" = 'renovate[bot]' ]; then
      echo 'commit format: skipped (Renovate PR)'; exit 0
    fi
    # --no-merges: merge commits (parent count > 1) are exempt.
    while IFS= read -r line; do
      sha=${line%% *}; subj=${line#* }
      valid "$subj" || report "commit ${sha:0:9}" "$subj"
    done < <(git log --no-merges --format='%H %s' "$base..$head")
    if [ "$has_title" = 1 ]; then valid "$title" || report "PR title" "$title"; fi
    [ "$bad" -eq 0 ] && { echo 'commit format: ok'; exit 0; }
    expected; exit 1 ;;
  *) sed -n '2,8p' "$0"; exit 2 ;;
esac
