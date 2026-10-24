/**
 * Run the existing registry integrity test with ONLY its three legacy iOS
 * resource paths changed to Android asset paths, in a temporary test copy.
 * Usage: node offline-registry-check.mjs /absolute/path/to/repository
 * Root may be omitted from docs/qa/security-audit-2026-10-24/evidence/.
 * Production files are read only. Source/data/dependencies remain the actual
 * current repository files, exposed through temporary symbolic links.
 */
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdtempSync, mkdirSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const root = process.argv[2] ? resolve(process.argv[2]) : fileURLToPath(new URL('../../../../', import.meta.url));
const original = readFileSync(join(root, 'engine/test/chain-expansion.test.mjs'), 'utf8');
const legacy = '../../BitToolSigner/Resources/';
assert.equal(original.split(legacy).length - 1, 3, 'The audited test must contain exactly three legacy paths.');
const adapted = original.replaceAll(legacy, '../../core/data/src/main/assets/registry/');
const temp = mkdtempSync(join(tmpdir(), 'bittool-registry-audit-'));
mkdirSync(join(temp, 'engine/test'), { recursive: true });
for (const relative of ['engine/src', 'engine/data', 'engine/node_modules', 'core']) {
  symlinkSync(join(root, relative), join(temp, relative), 'dir');
}
const testFile = join(temp, 'engine/test/chain-expansion.test.mjs');
writeFileSync(testFile, adapted);
console.log('Current source repository:', root);
console.log('Temporary test copy:', testFile);
console.log('Only replacement: ../../BitToolSigner/Resources/ -> ../../core/data/src/main/assets/registry/ (3 occurrences)');
const result = spawnSync(process.execPath, ['--test', '--test-name-pattern',
  '^Offline registry has unique exact identities, on-chain decimals and packed icons that match their hashes$', testFile],
  { stdio: 'inherit' });
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
