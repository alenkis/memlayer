#!/usr/bin/env bash
# Starts a detached Clojure socket REPL. Called by repl-eval.clj.
# Usage: bin/repl-daemon.sh <port> <pid-file> <log-file>
set -eu

PORT="$1"
PID_FILE="$2"
LOG_FILE="$3"

# Use tail -f /dev/null as stdin to keep the REPL alive
# (clojure.main exits on EOF, so /dev/null won't work)
tail -f /dev/null | clojure \
  -J--add-modules -Jjdk.incubator.vector \
  -J--enable-native-access=ALL-UNNAMED \
  "-J-Dclojure.server.repl={:port $PORT :accept clojure.core.server/repl}" \
  -M:dev >> "$LOG_FILE" 2>&1 &

# $! is the PID of the last backgrounded pipeline (the clojure process)
echo $! > "$PID_FILE"
disown
