#!/bin/sh
# User-installed hook for NSUserUnixTask. stdin is the lifetime of the window.
set -eu
base=$(dirname "$0")
cli="$base/deadliner-cli-binary"
[ -x "$cli" ] || exit 127
if [ -f "$base/deadliner-cli-profile" ]; then
    IFS= read -r DEADLINER_HOME < "$base/deadliner-cli-profile"
    export DEADLINER_HOME
fi
case "$#:$*" in "0:"|"1:--allow-writes") ;; *) exit 2 ;; esac
exec 3<&0
"$cli" --json serve "$@" </dev/null 3<&- &
service=$!
( IFS= read -r line <&3 || :; kill "$service" 2>/dev/null || : ) &
watcher=$!
trap 'kill "$watcher" "$service" 2>/dev/null || :' EXIT
trap 'exit 1' HUP INT TERM
wait "$service"
