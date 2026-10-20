#!/bin/bash
# Limits how many Gradle builds run at once across parallel agent worktrees (16 GB machine: every concurrent build
# costs a Gradle daemon plus Kotlin compilation memory). Adapted from Ownbit 5.0 tools/gradle-slot.sh.
# Usage: tools/gradle-slot.sh ./gradlew :feature:sign:compileDebugKotlin
# - waits for one of $SIGNER_GRADLE_SLOTS (default 3) mkdir-based slots under /tmp/bittool-signer-gradle-slots,
# - a slot whose owner process is gone is reclaimed,
# - the slot is released when the command exits (also on failure / interrupt).
# JAVA_HOME defaults to the JetBrains Runtime 21 that Android Studio installs.
set -u
SLOTS=${SIGNER_GRADLE_SLOTS:-3}
ROOT=/tmp/bittool-signer-gradle-slots
mkdir -p "$ROOT"
: "${JAVA_HOME:=$HOME/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home}"
export JAVA_HOME
# Worktrees have no local.properties (it is git-ignored): point AGP at the SDK through the environment.
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
export ANDROID_HOME

acquire() {
  local waited=0
  while true; do
    for i in $(seq 1 "$SLOTS"); do
      local d="$ROOT/slot$i"
      if mkdir "$d" 2>/dev/null; then
        echo $$ > "$d/pid"
        SLOT="$d"
        trap 'rm -rf "$SLOT"' EXIT
        return
      fi
      local pid; pid=$(cat "$d/pid" 2>/dev/null || true)
      if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then rm -rf "$d"; continue; fi
      if [ -z "$pid" ] && [ $(( $(date +%s) - $(stat -f %m "$d" 2>/dev/null || date +%s) )) -gt 60 ]; then rm -rf "$d"; fi
    done
    sleep 5; waited=$((waited+5))
    [ $((waited % 60)) -eq 0 ] && echo "[gradle-slot] waiting for a free build slot (${waited}s)…" >&2
  done
}

acquire
"$@"
