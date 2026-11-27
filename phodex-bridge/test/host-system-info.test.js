const test = require('node:test');
const assert = require('node:assert/strict');
const {linuxCounters, utilization, readSystemInfo} = require('../src/host-system-info');
const {downloadErrorDiagnostic} = require('../src/workspace-handler');
const fs = require('node:fs/promises');

test('CPU percentages use weighted deltas by logical CPU id, excluding guest double-counting and I/O wait', () => {
  const before = linuxCounters('cpu0 10 0 0 40 2 0 0 0 10 0\ncpu2 0 0 0 70 0 0 0 0 0 0');
  const after = linuxCounters('cpu2 0 0 0 77 3 0 0 0 0 0\ncpu0 40 0 0 40 2 0 0 0 30 0\ncpu4 5 0 0 1');
  assert.deepEqual(utilization(before, after), {percent:75, cores:[
    {id:2, percent:0}, {id:0, percent:100}, {id:4, percent:null},
  ]});
  assert.equal(utilization(before, before).percent, null);
  assert.throws(() => linuxCounters('cpu1 0 0 bad 5'), /Invalid CPU counters/);
});

test('a missing project filesystem keeps the CPU sample and disk diagnostic', async () => {
  const info = await readSystemInfo('/nonexistent-remodex-host-system-test', 'test-version', downloadErrorDiagnostic);
  assert.equal(info.disk.error.code, 'ENOENT');
  assert.match(info.disk.error.stack, /statfs/);
  assert.ok(info.cpu.cores.length > 0);
  assert.ok(Number.isInteger(info.cpu.percent));
  assert.equal(info.bridgeVersion, 'test-version');
});

test('used disk space excludes free blocks reserved for the filesystem', async t => {
  t.mock.method(fs, 'statfs', async () => ({blocks: 100, bfree: 40, bavail: 35, bsize: 4096}));
  const info = await readSystemInfo('/workspace', 'test-version', downloadErrorDiagnostic);
  assert.deepEqual(info.disk, {directory: '/workspace', total: 409600, available: 143360, used: 245760});
});
