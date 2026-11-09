// Fail packaging before publication if the native asset is absent or mismatched.
const { readFileSync } = require('node:fs');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const pkg = require(path.join(root, 'package.json'));
const binary = path.join(root, pkg.bin.deadliner);
if (process.platform !== 'darwin' || process.arch !== 'arm64') {
  throw new Error('Package the macOS arm64 release on an Apple Silicon Mac.');
}
const bytes = readFileSync(binary);
if (bytes.readUInt32LE(0) !== 0xfeedfacf || bytes.readUInt32LE(4) !== 0x0100000c) {
  throw new Error('bin/deadliner must be a Mach-O arm64 executable. Run npm run build.');
}
const result = spawnSync(binary, ['version'], { encoding: 'utf8', timeout: 10000 });
if (result.error || result.status !== 0) throw new Error('Cannot execute the packaged CLI.');
const version = JSON.parse(result.stdout);
if (!version.ok || version.data.cli !== pkg.version) throw new Error('CLI/package version mismatch.');
const core = readFileSync(path.join(root, 'core-revision.txt'), 'utf8').trim();
if (!version.data.core.startsWith(`DeadlinerCore-${core.slice(0, 6)}-`)) {
  throw new Error('CLI/core-revision.txt mismatch. Rebuild against the recorded Core revision.');
}
console.error(`Validated ${pkg.name}@${pkg.version} (${version.data.core})`);
