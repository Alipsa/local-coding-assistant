#!/usr/bin/env bash
# run_with_timeout <timeout_s> <grace_s> [--] <cmd> [args...]
#
# Runs <cmd> with a hard wall-clock budget. If it's still running after
# <timeout_s> seconds, sends SIGTERM to its whole process group; if it's
# still alive <grace_s> seconds after that, sends SIGKILL. Returns <cmd>'s
# real exit code if it finished on its own, or 124 if it had to be killed
# (matching GNU coreutils `timeout`'s convention).
#
# Requires bash job control (`set -m`) so the backgrounded command becomes
# its own process-group leader — a bare `kill` on the direct child PID is
# not enough: pip/python subprocesses can spawn children of their own, and
# a process blocked in a C-level blocking syscall can ignore a lone SIGTERM
# until that syscall returns.
run_with_timeout() {
  local timeout_s="$1" grace_s="$2"
  shift 2
  [[ "${1:-}" == "--" ]] && shift

  local killed_flag
  killed_flag=$(mktemp)
  rm -f "$killed_flag"

  set -m
  "$@" &
  local cmd_pid=$!
  set +m

  set -m
  (
    sleep "$timeout_s"
    if kill -0 "$cmd_pid" 2>/dev/null; then
      : > "$killed_flag"
      kill -TERM "-$cmd_pid" 2>/dev/null
      sleep "$grace_s"
      kill -KILL "-$cmd_pid" 2>/dev/null
    fi
  ) &
  local watchdog_pid=$!
  set +m

  local status=0
  wait "$cmd_pid" 2>/dev/null || status=$?

  kill -- "-$watchdog_pid" 2>/dev/null
  wait "$watchdog_pid" 2>/dev/null

  if [[ -f "$killed_flag" ]]; then
    rm -f "$killed_flag"
    return 124
  fi
  return "$status"
}
