// The child inherits the validated, open archive as stdin and never extracts to disk.
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const path = require('node:path');

let active = 0;
async function previewArchive(file, method, params, streams, metadata, verify) {
  const list = method === 'workspace/archiveList';
  const cursor = list ? params.offset : params.index;
  if (!Number.isSafeInteger(cursor) || cursor < 0 || path.extname(metadata.path).toLowerCase() !== '.zip') {
    throw Object.assign(new Error('Invalid ZIP preview request'), {errorCode: 'invalid_archive_request'});
  }
  if (active >= 3) throw Object.assign(new Error('ZIP reader busy. Try again.'), {errorCode: 'archive_busy'});
  const limit = streams ? 8 * 1024 * 1024 : 640 * 1024;
  active++;
  let timer;
  try {
    const child = spawn('python3', [path.join(__dirname, 'archive-preview.py'), list ? 'list' : 'read',
      String(cursor), String(limit)], {stdio: [file.fd, 'pipe', 'pipe']});
    let timedOut = false;
    timer = setTimeout(() => { timedOut = true; child.kill('SIGKILL'); }, 15000);
    const output = [], errors = [];
    let size = 0;
    child.stdout.on('data', chunk => {
      size += chunk.length;
      if (size <= limit) output.push(chunk);
      else child.kill('SIGKILL');
    });
    child.stderr.on('data', chunk => errors.push(chunk));
    const [status, signal] = await once(child, 'close');
    if (status !== 0 || size > limit) {
      const stderr = Buffer.concat(errors).toString();
      let detail;
      try { detail = JSON.parse(stderr); } catch { /* Preserve non-JSON interpreter errors below. */ }
      const cause = new Error(detail?.stack || stderr || 'ZIP reader exited with ' + (signal || status));
      const code = timedOut ? 'archive_timeout' : size > limit ? 'archive_preview_limit' : detail?.code || 'archive_reader_failed';
      const message = timedOut ? 'ZIP preview timed out' : detail?.message || cause.message;
      throw Object.assign(new Error(message, {cause}), {errorCode: code});
    }
    await verify();
    const data = Buffer.concat(output);
    if (list) return {...JSON.parse(data), revision:metadata.revision};
    if (streams) return streams.grant({...metadata, byteLength:data.length, data});
    return {revision:metadata.revision, byteLength:data.length, dataBase64:data.toString('base64')};
  } finally { clearTimeout(timer); active--; }
}
module.exports = {previewArchive};
