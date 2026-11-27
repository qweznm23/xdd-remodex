const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {execFileSync} = require('node:child_process');
const {handleWorkspaceRequest} = require('../src/workspace-handler');

const rpc = (method, params) => new Promise(resolve => {
  assert.equal(handleWorkspaceRequest(JSON.stringify({id:1, method, params}), raw => resolve(JSON.parse(raw))), true);
});

test('workspace ZIP directory pages and selected entries use the bound archive revision', async t => {
  const cwd = fs.mkdtempSync(path.join(os.tmpdir(), 'remodex-zip-'));
  t.after(() => fs.rmSync(cwd, {recursive:true, force:true}));
  const archive = path.join(cwd, '聊天.zip');
  execFileSync('python3', [path.join(__dirname, 'fixtures/archive-preview.py'), archive]);
  const params = {cwd, path:archive, inline:true};
  const {result: info} = await rpc('workspace/fileInfo', params);
  assert.equal(info.previewKind, 'archive');
  params.revision = info.revision;
  const list = (offset) => rpc('workspace/archiveList', {...params, offset});
  const read = (index) => rpc('workspace/archiveEntry', {...params, index});
  const {result:first} = await list(0);
  assert.equal(first.total, 71);
  assert.equal(first.nextOffset, 64);
  assert.deepEqual(first.entries.slice(0, 6).map(e => e.kind), ['text','image','none','none','none','none']);
  assert.equal(first.entries[2].directory, true);
  assert.equal(first.entries[1].name, '图片/图片.jpg');
  const {result:last} = await list(first.nextOffset);
  assert.equal(last.entries[0].index, 64);
  assert.equal(last.nextOffset, 71);
  for (const [index, content] of [[0, '聊天记录'], [1, 'jpeg fixture'], [70, '64']]) {
    const {result} = await read(index);
    assert.equal(Buffer.from(result.dataBase64, 'base64').toString(), content);
    assert.equal(result.revision, params.revision);
  }
  for (const index of [2, 3, 4, 5]) {
    const {error} = await read(index);
    assert.equal(error.data.errorCode, 'archive_entry_unsupported');
    assert.match(error.data.diagnostic.cause.message, /PreviewError/);
  }
  assert.equal((await read(72)).error.data.errorCode, 'invalid_archive_offset');
  fs.writeFileSync(archive, 'broken ZIP');
  assert.equal((await read(0)).error.data.errorCode, 'file_changed');
  params.revision = (await rpc('workspace/fileInfo', params)).result.revision;
  const {error} = await list(0);
  assert.equal(error.data.errorCode, 'invalid_zip');
  assert.equal(error.data.operation, 'workspace/archiveList');
  assert.match(error.data.diagnostic.cause.message, /BadZipFile/);
});
