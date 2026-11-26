const { spawnSync } = require('node:child_process');
const python = process.platform === 'win32' ? 'python' : 'python3';
const result = spawnSync(python, process.argv.slice(2), { stdio: 'inherit' });
if (result.error) console.error(result.error.message);
process.exitCode = result.status ?? 1;
