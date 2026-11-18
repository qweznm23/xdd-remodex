const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { createHash } = require('crypto');
const { createWorkspaceUploadHandler, MAX_UPLOAD_BYTES, UPLOAD_CHUNK_BYTES } = require('../src/workspace-upload');
const { handleWorkspaceRequest } = require('../src/workspace-handler');
const hash = data => createHash('sha256').update(data).digest('hex');

function setup(t, options = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'remodex-upload-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  return { root, upload: createWorkspaceUploadHandler({ root, ...options }) };
}

test('100 MB file is uploaded in bounded chunks, verified, and retained on a late abort', async t => {
  const { upload } = setup(t);
  const begin = await upload('workspace/uploadBegin', { fileName: 'large archive.zip', byteLength: MAX_UPLOAD_BYTES });
  const bytes = Buffer.alloc(UPLOAD_CHUNK_BYTES, 0x73);
  const whole = createHash('sha256');
  for (let offset = 0; offset < MAX_UPLOAD_BYTES; offset += bytes.length) {
    const result = await upload('workspace/uploadChunk', { uploadId: begin.uploadId, offset, dataBase64: bytes.toString('base64'), sha256: hash(bytes) });
    assert.equal(result.nextOffset, offset + bytes.length); whole.update(bytes);
  }
  const digest = whole.digest('hex');
  const finish = await upload('workspace/uploadFinish', { uploadId: begin.uploadId, byteLength: MAX_UPLOAD_BYTES, sha256: digest });
  assert.equal(fs.statSync(finish.path).size, MAX_UPLOAD_BYTES);
  const actualHash = createHash('sha256');
  for await (const chunk of fs.createReadStream(finish.path)) actualHash.update(chunk);
  assert.equal(actualHash.digest('hex'), digest);
  assert.equal((await upload('workspace/uploadAbort', { uploadId: begin.uploadId })).aborted, false);
  assert.equal(fs.existsSync(finish.path), true);
});

test('rejects oversize declarations, traversal, corrupt chunks and incorrect final hash', async t => {
  const { upload, root } = setup(t, { maxBytes: 8 });
  await assert.rejects(upload('workspace/uploadBegin', { fileName: 'a', byteLength: 9 }), { errorCode: 'upload_too_large' });
  await assert.rejects(upload('workspace/uploadBegin', { fileName: '../a' }), { errorCode: 'invalid_upload_name' });
  const { uploadId } = await upload('workspace/uploadBegin', { fileName: 'a' });
  await assert.rejects(upload('workspace/uploadChunk', { uploadId, offset: 0, dataBase64: 'YWJj', sha256: 'bad' }), { errorCode: 'upload_checksum_mismatch' });
  const params = { uploadId, offset: 0, dataBase64: 'YWJj', sha256: hash('abc') };
  assert.equal((await upload('workspace/uploadChunk', params)).nextOffset, 3);
  assert.equal((await upload('workspace/uploadChunk', params)).nextOffset, 3, 'same acknowledged chunk is idempotent');
  await assert.rejects(upload('workspace/uploadChunk', { ...params, offset: 1 }), { errorCode: 'upload_offset_mismatch' });
  await assert.rejects(upload('workspace/uploadFinish', { uploadId, byteLength: 3, sha256: 'bad' }), { errorCode: 'upload_checksum_mismatch' });
  await upload('workspace/uploadAbort', { uploadId });
  assert.deepEqual(fs.readdirSync(root), []);
});

test('unknown-size stream is capped and empty files can finish', async t => {
  const { upload } = setup(t, { maxBytes: 2 });
  const { uploadId } = await upload('workspace/uploadBegin', { fileName: 'unknown.bin' });
  await assert.rejects(upload('workspace/uploadChunk', { uploadId, offset: 0, dataBase64: 'YWJj', sha256: hash('abc') }), { errorCode: 'upload_too_large' });
  await upload('workspace/uploadAbort', { uploadId });
  const empty = await upload('workspace/uploadBegin', { fileName: 'empty.txt', byteLength: 0 });
  const result = await upload('workspace/uploadFinish', { uploadId: empty.uploadId, byteLength: 0, sha256: hash('') });
  assert.equal(fs.statSync(result.path).size, 0);
});

test('filesystem errors keep their original code and stack', async t => {
  const { root } = setup(t);
  const file = path.join(root, 'file'); fs.writeFileSync(file, 'x');
  const upload = createWorkspaceUploadHandler({ root: path.join(file, 'directory') });
  await assert.rejects(upload('workspace/uploadBegin', { fileName: 'a' }), error => {
    assert.equal(error.code, 'ENOTDIR'); assert.ok(error.stack.includes('Error')); return true;
  });
});

test('upload RPC errors retain operation, business code and diagnostic stack', async () => {
  const reply = await new Promise(resolve => {
    assert.equal(handleWorkspaceRequest(JSON.stringify({ id: 'upload-error-test', method: 'workspace/uploadChunk', params: { uploadId: 'nonexistent' } }), raw => resolve(JSON.parse(raw))), true);
  });
  assert.equal(reply.error.code, -32000);
  assert.equal(reply.error.data.errorCode, 'upload_not_found');
  assert.equal(reply.error.data.operation, 'workspace/uploadChunk');
  assert.equal(reply.error.data.stage, 'workspace_file_transfer');
  assert.equal(reply.error.data.diagnostic.type, 'Error');
  assert.ok(reply.error.data.diagnostic.stack.includes('Upload expired'));
});
