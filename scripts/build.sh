#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CORE=${DEADLINER_CORE_DIR:-$ROOT/../../DeadlinerCore}
[ "$(uname -sm)" = 'Darwin arm64' ] || { echo 'Build requires macOS Apple Silicon' >&2; exit 1; }
[ -f "$CORE/gradlew" ] || { echo 'Set DEADLINER_CORE_DIR to a DeadlinerCore checkout' >&2; exit 1; }
mkdir -p "$ROOT/.build/core" "$ROOT/bin"
# Compile a disposable source snapshot; never inject CLI code into the Core checkout.
rsync -a --delete --exclude .git --exclude .gradle --exclude .kotlin --exclude build --exclude .private "$CORE/" "$ROOT/.build/core/"
python3 - "$ROOT" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
build = root / '.build/core/shared/build.gradle.kts'
s = build.read_text()
s += '\n' + '''
kotlin {
    sourceSets.named("macosArm64Main") { kotlin.srcDir("''' + str(root / 'src') + '''") }
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
"$ROOT/.build/core/gradlew" -PdeadlinerCoreCommit="$CORE_COMMIT" -PdeadlinerCoreCompileBranch=cli -p "$ROOT/.build/core" -PlifiMode=stub :shared:linkDeadlinerDebugExecutableMacosArm64 "$@"
cp "$ROOT/.build/core/shared/build/bin/macosArm64/deadlinerDebugExecutable/deadliner.kexe" "$ROOT/bin/deadliner"
echo "Built $ROOT/bin/deadliner"
