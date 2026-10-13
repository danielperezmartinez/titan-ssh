#!/usr/bin/env bash
# Turn lock for driving a shared Android emulator (install, input, screenshots)
# from several agent sessions or worktrees at once. See README.md.
#
#   emulator-lock.sh acquire [--wait] [--timeout SECS] [--note TEXT]
#   emulator-lock.sh release [--force]
#   emulator-lock.sh status
#
# The lock is a directory created with mkdir (atomic, also on Windows) outside
# any worktree, one per emulator serial. Calling acquire again while holding it
# renews it; a lock not renewed for TITAN_EMULATOR_LOCK_TTL seconds is stale and
# the next acquire takes it over.
set -u

usage() {
  sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

ttl=${TITAN_EMULATOR_LOCK_TTL:-1200}
poll=${TITAN_EMULATOR_LOCK_POLL:-5}
serial=${ANDROID_SERIAL:-default}

base=${TITAN_EMULATOR_LOCK_DIR:-}
if [ -z "$base" ]; then
  if [ -n "${LOCALAPPDATA:-}" ]; then
    base=$LOCALAPPDATA
    command -v cygpath >/dev/null 2>&1 && base=$(cygpath -u "$base")
  else
    base=${XDG_CACHE_HOME:-$HOME/.cache}
  fi
  base=$base/titan-ssh-dev/locks
fi
lock=$base/emulator-$(printf '%s' "$serial" | tr -c 'A-Za-z0-9._-' '_').lock
info=$lock/owner

# Who holds the lock: the session's own name if it set one, else its worktree.
owner=${TITAN_EMULATOR_LOCK_OWNER:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}

now() { date +%s; }

# Seconds since the lock was last renewed (owner file, or the bare directory
# while its creator is still writing the file).
age() {
  local f=$info
  [ -f "$f" ] || f=$lock
  echo $(( $(now) - $(stat -c %Y "$f" 2>/dev/null || now) ))
}

field() { sed -n "s/^$1=//p" "$info" 2>/dev/null; }

write_info() {
  local since=$1 note=$2
  {
    echo "owner=$owner"
    echo "branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
    echo "serial=$serial"
    echo "since=$since"
    echo "note=$note"
  } >"$info.tmp" && mv -f "$info.tmp" "$info"
}

describe() {
  local a; a=$(age)
  echo "held by $(field owner) (branch $(field branch)) since $(date -d "@$(field since)" '+%F %T' 2>/dev/null || field since), renewed ${a}s ago${1:+; $1}"
  local n; n=$(field note)
  [ -n "$n" ] && echo "  note: $n"
}

# One attempt. 0 = we hold it now, 1 = someone else holds a live lock.
try_acquire() {
  local note=$1
  mkdir -p "$base"
  if mkdir "$lock" 2>/dev/null; then
    write_info "$(now)" "$note"
    echo "ACQUIRED $lock"
    return 0
  fi
  if [ "$(field owner)" = "$owner" ]; then
    write_info "$(field since)" "${note:-$(field note)}"
    echo "RENEWED $lock"
    return 0
  fi
  if [ "$(age)" -ge "$ttl" ]; then
    # Stale: move it aside first, so only one waiter can win the takeover.
    local prev; prev=$(describe)
    if mv "$lock" "$lock.stale.$$" 2>/dev/null; then
      rm -rf "$lock.stale.$$"
      echo "taking over a stale lock: $prev"
      try_acquire "$note"
      return
    fi
  fi
  return 1
}

cmd=${1:-}; [ -n "$cmd" ] || usage; shift
case $cmd in
  acquire)
    wait=0 timeout=0 note=
    while [ $# -gt 0 ]; do
      case $1 in
        --wait) wait=1 ;;
        --timeout) timeout=$2; shift ;;
        --note) note=$2; shift ;;
        *) usage ;;
      esac
      shift
    done
    start=$(now) last=
    while :; do
      try_acquire "$note" && exit 0
      cur=$(field owner)
      if [ "$cur" != "$last" ]; then echo "BUSY $(describe)"; last=$cur; fi
      [ $wait -eq 1 ] || exit 1
      if [ "$timeout" -gt 0 ] && [ $(( $(now) - start )) -ge "$timeout" ]; then
        echo "TIMEOUT after ${timeout}s"; exit 3
      fi
      sleep "$poll"
    done
    ;;
  release)
    force=0; [ "${1:-}" = --force ] && force=1
    if [ ! -d "$lock" ]; then echo "FREE (nothing to release)"; exit 0; fi
    if [ $force -eq 0 ] && [ "$(field owner)" != "$owner" ]; then
      echo "NOT OWNER $(describe)"; exit 1
    fi
    rm -rf "$lock" && echo "RELEASED $lock"
    ;;
  status)
    if [ -d "$lock" ]; then
      a=$(age)
      if [ "$a" -ge "$ttl" ]; then echo "STALE $(describe "older than ${ttl}s, the next acquire takes it")"
      else echo "BUSY $(describe)"; fi
    else
      echo "FREE $lock"
    fi
    ;;
  *) usage ;;
esac
