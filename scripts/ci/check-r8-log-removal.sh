#!/usr/bin/env bash
# Proves that R8 removed every android.util.Log call from the app's own code in the release APK
# (threat model T-STO-3: scanned content must never reach logcat). Full gate only.
#
# Method (the most reliable of the options, and why):
#  - Reading the dex is the ground truth: the shipped bytecode either calls Log or does not. Greping
#    mapping.txt / usage.txt is weaker (usage.txt lists removed members, not removed call sites).
#  - `dexdump -d` (Android SDK build-tools) disassembles every classes*.dex of the APK.
#  - R8 obfuscates the app's classes, so "app code" is not recognisable by package in the dex. The
#    release mapping.txt maps com.lookfirst.* originals to their final names; only those classes
#    are inspected. Libraries (AndroidX, Hilt, ...) may legitimately call Log and are out of scope.
#  - Controls so the check cannot pass vacuously: the awk filter is self-tested on a synthetic dump
#    (--self-test), at least one app class must be found in the dex, and R8's merged configuration
#    must contain the assumenosideeffects rule for android.util.Log.
#  - Known limit: while the app has no Log call at all, a pass is expected and proves only the
#    plumbing; it starts to prove the removal as soon as app code logs.
#
# usage: check-r8-log-removal.sh [--self-test]
set -euo pipefail

# stdin: dexdump -d output; $1: file with the final descriptors (Lx/y;) of the app classes.
# Counts, in app classes: android.util.Log calls, System.out / System.err reads, printStackTrace calls
# (none is removed by R8 unless a rule says so, all can leak scanned content to logcat).
# Prints "<app classes seen> <calls from app classes>".
scan() {
  awk -v names="$1" '
    BEGIN { while ((getline l < names) > 0) app[l] = 1 }
    /Class descriptor/ {
      match($0, /\x27L[^\x27]*;\x27/)
      cur = substr($0, RSTART + 1, RLENGTH - 2)
      inapp = (cur in app)
      if (inapp) seen++
      next
    }
    inapp && /Landroid\/util\/Log;\.[A-Za-z]+:|Ljava\/lang\/System;\.(out|err):|;\.printStackTrace:/ { calls++; print "LOG CALL in " cur ": " $0 > "/dev/stderr" }
    END { print seen + 0, calls + 0 }'
}

if [ "${1:-}" = --self-test ]; then
  tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
  printf 'La/b;\n' > "$tmp/names"
  log='    invoke-static {v0, v1}, Landroid/util/Log;.d:(Ljava/lang/String;Ljava/lang/String;)I'
  clean=$(printf "  Class descriptor  : 'La/b;'\n    invoke-static {v0}, Ljava/lang/String;.valueOf:(I)Ljava/lang/String;\n" | scan "$tmp/names" 2>/dev/null)
  dirty=$(printf "  Class descriptor  : 'La/b;'\n%s\n" "$log" | scan "$tmp/names" 2>/dev/null)
  other=$(printf "  Class descriptor  : 'Lc/d;'\n%s\n" "$log" | scan "$tmp/names" 2>/dev/null)
  sysout=$(printf "  Class descriptor  : 'La/b;'\n    sget-object v0, Ljava/lang/System;.out:Ljava/io/PrintStream;\n" | scan "$tmp/names" 2>/dev/null)
  stack=$(printf "  Class descriptor  : 'La/b;'\n    invoke-virtual {v0}, Ljava/lang/Throwable;.printStackTrace:()V\n" | scan "$tmp/names" 2>/dev/null)
  if [ "$sysout" != "1 1" ] || [ "$stack" != "1 1" ]; then echo "self-test failed: '$sysout' '$stack'"; exit 1; fi
  if [ "$clean" != "1 0" ] || [ "$dirty" != "1 1" ] || [ "$other" != "0 0" ]; then
    echo "self-test failed: '$clean' '$dirty' '$other'"; exit 1
  fi
  echo "r8 log check self-test: ok"; exit 0
fi

apks=$(ls app/build/outputs/apk/release/*.apk 2>/dev/null || true)
[ -n "$apks" ] && [ "$(printf '%s\n' "$apks" | wc -l)" -eq 1 ] || { echo "expected exactly one release APK, found: ${apks:-none}"; exit 1; }
apk=$apks
mapping=app/build/outputs/mapping/release/mapping.txt
config=app/build/outputs/mapping/release/configuration.txt
[ -f "$apk" ] && [ -f "$mapping" ] && [ -f "$config" ] || { echo "release APK or R8 outputs missing"; exit 1; }

dexdump=$(ls "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -n1 || true)
[ -n "$dexdump" ] || { echo "dexdump not found in the SDK build-tools"; exit 1; }

grep -Eq 'assumenosideeffects +class +android\.util\.Log' "$config" \
  || { echo "R8 configuration has no assumenosideeffects rule for android.util.Log"; exit 1; }

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
# mapping.txt: "com.lookfirst.Foo -> a.b:" (class lines start at column 0).
awk '/^com\.lookfirst\..* -> .*:$/ { n = $3; sub(/:$/, "", n); gsub(/\./, "/", n); print "L" n ";" }' "$mapping" > "$tmp/names"
[ -s "$tmp/names" ] || { echo "no com.lookfirst class in mapping.txt"; exit 1; }

unzip -q -o "$apk" 'classes*.dex' -d "$tmp/dex"
total_seen=0; total_calls=0
for dex in "$tmp"/dex/classes*.dex; do
  "$dexdump" -d "$dex" > "$tmp/dump.txt" || { echo "dexdump failed on $dex"; exit 1; }
  [ -s "$tmp/dump.txt" ] || { echo "dexdump output is empty for $dex"; exit 1; }
  read -r seen calls <<< "$(scan "$tmp/names" < "$tmp/dump.txt")"
  total_seen=$((total_seen + seen)); total_calls=$((total_calls + calls))
done
echo "app classes in the dex: $total_seen / $(wc -l < "$tmp/names") mapped; Log / System.out / printStackTrace calls from app classes: $total_calls"
[ "$total_seen" -gt 0 ] || { echo "no app class found in the dex: the check would be vacuous"; exit 1; }
[ "$total_calls" -eq 0 ] || { echo "R8 left Log / System.out / printStackTrace calls in the app code"; exit 1; }
echo "R8 removed all android.util.Log calls from the app code"
