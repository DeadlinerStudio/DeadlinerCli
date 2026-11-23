#!/bin/sh
# Run once from the terminal whose CLI/profile the Mac preview should use.
set -eu
umask 077
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
selected=$(command -v deadliner) || { echo 'deadliner is missing from PATH' >&2; exit 1; }
case "$selected" in /*) ;; *) echo 'Expected an absolute deadliner executable in PATH' >&2; exit 1;; esac
binary=$(realpath "$selected")
dest="$HOME/Library/Application Scripts/com.deadliner.macos"
mkdir -p "$dest"
stage=$(mktemp -d "$dest/.cli-install.XXXXXX")
trap 'rm -rf "$stage"' EXIT
ln -s "$binary" "$stage/deadliner-cli-binary"
install -m 0700 "$root/macos-readonly-launcher.sh" "$stage/deadliner-readonly"
printf '%s\n' "${DEADLINER_HOME:-$HOME/.local/share/deadliner}" > "$stage/deadliner-cli-profile"
mv -f "$stage/deadliner-cli-binary" "$dest/deadliner-cli-binary"
mv -f "$stage/deadliner-cli-profile" "$dest/deadliner-cli-profile"
mv -f "$stage/deadliner-readonly" "$dest/deadliner-readonly"
echo "Enabled read-only macOS startup for $binary"
