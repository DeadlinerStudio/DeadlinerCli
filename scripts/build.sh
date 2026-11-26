#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MODE=${DEADLINER_LIFI_MODE:-stub}
case "$MODE" in private|stub) ;; *) echo "Expected private or stub LiFi mode" >&2; exit 2 ;; esac
CORE=${DEADLINER_CORE_DIR:-$ROOT/../../DeadlinerCore}
[ "$(uname -sm)" = 'Darwin arm64' ] || { echo 'Build requires macOS Apple Silicon' >&2; exit 1; }
[ -f "$CORE/gradlew" ] || { echo 'Set DEADLINER_CORE_DIR to a DeadlinerCore checkout' >&2; exit 1; }
mkdir -p "$ROOT/.build/core" "$ROOT/bin"
# Compile a disposable source snapshot; never inject CLI code into the Core checkout.
rsync -a --delete --exclude .git --exclude .gradle --exclude .kotlin --exclude build --exclude .private "$CORE/" "$ROOT/.build/core/"
if [ "$MODE" = private ]; then
    mkdir -p "$ROOT/.build/core/.private/lifi"
    rsync -a --delete --exclude .git "$CORE/.private/lifi/" "$ROOT/.build/core/.private/lifi/"
fi
python3 - "$ROOT" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
build = root / '.build/core/shared/build.gradle.kts'
s = build.read_text()
s += '\n' + '''
kotlin {
    sourceSets.named("macosArm64Main") { kotlin.srcDir("''' + str(root / 'src/common') + '''") }
    sourceSets.named("macosArm64Main") { kotlin.srcDir("''' + str(root / 'src/macos') + '''") }
    macosArm64 {
        binaries.executable("deadliner") {
            entryPoint = "com.deadliner.cli.main"
        }
    }
}
'''
build.write_text(s)
PY
CORE_COMMIT=$(git -C "$CORE" rev-parse HEAD 2>/dev/null || echo unknown)
"$ROOT/.build/core/gradlew" -PdeadlinerCoreCommit="$CORE_COMMIT" -PdeadlinerCoreCompileBranch=cli -p "$ROOT/.build/core" -PlifiMode="$MODE" :shared:linkDeadlinerDebugExecutableMacosArm64 "$@"
cp "$ROOT/.build/core/shared/build/bin/macosArm64/deadlinerDebugExecutable/deadliner.kexe" "$ROOT/bin/deadliner"
echo "Built $ROOT/bin/deadliner"
