const fs = require('fs');
const path = require('path');
const { randomUUID, createHash } = require('crypto');
const { resolveCodexHome } = require('./codex-home');

const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;
const UPLOAD_CHUNK_BYTES = 256 * 1024;
const UPLOAD_TTL_MS = 60 * 60 * 1000;
const methods = new Set(['workspace/uploadBegin', 'workspace/uploadChunk', 'workspace/uploadFinish', 'workspace/uploadAbort']);

function uploadError(code, message) {
  return Object.assign(new Error(message), { errorCode: code });
}

function workspaceUploadRoot() {
  return path.resolve(resolveCodexHome(), 'remodex_uploads');
}

function isCompletedUploadPath(root, candidate) {
  if (typeof candidate !== 'string' || !path.isAbsolute(candidate)) return false;
  const [id, name, ...rest] = path.relative(root, candidate).split(path.sep);
  return /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(id)
    && Boolean(name && name !== '.upload-part' && rest.length === 0);
}

function createWorkspaceUploadHandler({ root = workspaceUploadRoot(), maxBytes = MAX_UPLOAD_BYTES } = {}) {
  const sessions = new Map();
  let cleanupTimer;
  async function discard(id, state) {
    if (state.handle) { await state.handle.close(); state.handle = null; }
    if (!state.result) await fs.promises.rm(state.directory, { recursive: true, force: true });
    sessions.delete(id);
  }
  async function sweep() {
    for (const [id, state] of sessions) {
      if (!state.busy && Date.now() - state.updated > UPLOAD_TTL_MS) await discard(id, state);
    }
    // Recover abandoned partial uploads after a bridge restart; completed files are retained.
    await fs.promises.mkdir(root, { recursive: true, mode: 0o700 });
    for (const entry of await fs.promises.readdir(root, { withFileTypes: true })) {
      if (!entry.isDirectory() || !/^[a-f0-9-]{36}$/.test(entry.name) || sessions.has(entry.name)) continue;
      const partial = path.join(root, entry.name, '.upload-part');
      try {
        const stat = await fs.promises.stat(partial);
        if (Date.now() - stat.mtimeMs > UPLOAD_TTL_MS) await fs.promises.rm(path.dirname(partial), { recursive: true });
      } catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
  }
  return async function upload(method, params) {
    if (!methods.has(method)) throw uploadError('unknown_upload_method', 'Unknown upload operation');
    if (method === 'workspace/uploadBegin') {
      await sweep();
      if (!cleanupTimer) {
        cleanupTimer = setInterval(() => sweep().catch(error => console.error('[remodex] upload cleanup failed', error)), 5 * 60 * 1000);
        cleanupTimer.unref();
      }
      const fileName = params.fileName;
      const size = params.byteLength;
      if (typeof fileName !== 'string' || !fileName.trim() || fileName === '.' || fileName === '..' || fileName === '.upload-part' || /[/\\\x00-\x1f]/.test(fileName) || Buffer.byteLength(fileName) > 240) {
        throw uploadError('invalid_upload_name', 'Invalid upload filename');
      }
      if (size != null && (!Number.isSafeInteger(size) || size < 0 || size > maxBytes)) throw uploadError('upload_too_large', `File exceeds upload limit (${maxBytes} bytes)`);
      if ([...sessions.values()].filter(s => !s.result).length >= 4) throw uploadError('upload_busy', 'Too many uploads are active');
      const uploadId = randomUUID();
      const directory = path.join(root, uploadId);
      await fs.promises.mkdir(directory, { mode: 0o700 });
      let handle;
      try { handle = await fs.promises.open(path.join(directory, '.upload-part'), 'wx', 0o600); }
      catch (error) {
        try { await fs.promises.rm(directory, { recursive: true, force: true }); }
        catch (cleanup) { error.suppressed = [...(error.suppressed || []), cleanup]; }
        throw error;
      }
      sessions.set(uploadId, { directory, fileName, size, handle, offset: 0, hash: createHash('sha256'), updated: Date.now(), busy: false });
      return { uploadId, maxBytes, chunkBytes: UPLOAD_CHUNK_BYTES };
    }
    const state = sessions.get(params.uploadId);
    if (!state) {
      if (method === 'workspace/uploadAbort') return { aborted: true };
      throw uploadError('upload_not_found', 'Upload expired or bridge restarted; select the file again');
    }
    if (state.busy) throw uploadError('upload_busy', 'An upload operation is already in progress');
    state.busy = true;
    state.updated = Date.now();
    try {
      if (method === 'workspace/uploadAbort') { await discard(params.uploadId, state); return { aborted: !state.result }; }
      if (method === 'workspace/uploadFinish') {
        if (state.result) return state.result;
        const sha256 = state.hash.copy().digest('hex');
        if (params.byteLength !== state.offset || (state.size != null && state.size !== state.offset) || params.sha256 !== sha256) {
          throw uploadError('upload_checksum_mismatch', 'Uploaded file size or SHA-256 does not match');
        }
        await state.handle.sync();
        await state.handle.close(); state.handle = null;
        const destination = path.join(state.directory, state.fileName);
        await fs.promises.rename(path.join(state.directory, '.upload-part'), destination);
        state.result = { path: destination, fileName: state.fileName, byteLength: state.offset, sha256 };
        return state.result;
      }
      if (state.result) throw uploadError('upload_finished', 'Upload has already completed');
      const encoded = params.dataBase64;
      if (typeof encoded !== 'string' || !encoded.length || encoded.length > 4 * Math.ceil(UPLOAD_CHUNK_BYTES / 3) || !/^[A-Za-z0-9+/]*={0,2}$/.test(encoded)) throw uploadError('invalid_upload_chunk', 'Invalid upload chunk encoding or size');
      const data = Buffer.from(encoded, 'base64');
      const sha256 = createHash('sha256').update(data).digest('hex');
      if (!data.length || data.length > UPLOAD_CHUNK_BYTES || data.toString('base64') !== encoded || params.sha256 !== sha256) throw uploadError('upload_checksum_mismatch', 'Upload chunk checksum does not match');
      if (state.last?.offset === params.offset && state.last.sha256 === sha256) return { nextOffset: state.offset };
      if (params.offset !== state.offset) throw uploadError('upload_offset_mismatch', 'Upload offset does not match');
      if (state.offset + data.length > maxBytes || (state.size != null && state.offset + data.length > state.size)) throw uploadError('upload_too_large', 'Upload exceeds its declared size or the file limit');
      let written = 0;
      while (written < data.length) {
        const result = await state.handle.write(data, written, data.length - written, state.offset + written);
        if (!result.bytesWritten) throw uploadError('upload_write_stalled', 'Upload storage made no progress');
        written += result.bytesWritten;
      }
      state.hash.update(data);
      state.last = { offset: state.offset, sha256 };
      state.offset += data.length;
      return { nextOffset: state.offset };
    } finally { state.busy = false; }
  };
}

module.exports = { createWorkspaceUploadHandler, workspaceUploadRoot, isCompletedUploadPath, MAX_UPLOAD_BYTES, UPLOAD_CHUNK_BYTES, uploadMethods: methods };
