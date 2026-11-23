const fs = require('node:fs');
const path = require('node:path');
const { pipeline } = require('node:stream/promises');

const manifestOrApk = /^(?:update\.json|remodex-[A-Za-z0-9._-]+\.apk)$/;
const legacyArtifact = /^(?:index\.html|SHA256SUMS|LICENSE-remodex\.txt|remodex-public-ws\.patch|xdd-remodex-server-[A-Za-z0-9._-]+\.tar\.gz|imes_gc-release-[0-9.]+\.apk)$/;

function mediaType(name) {
  if (name.endsWith('.apk')) return 'application/vnd.android.package-archive';
  if (name.endsWith('.json')) return 'application/json';
  if (name.endsWith('.gz')) return 'application/gzip';
  if (name.endsWith('.html')) return 'text/html; charset=utf-8';
  return 'text/plain; charset=utf-8';
}

// One range, including open-ended and suffix forms. Clamp before converting to Number.
function byteRange(header, size) {
  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!match || !(match[1] || match[2]) || !size) return null;
  const total = BigInt(size);
  const first = match[1] ? BigInt(match[1]) : total - BigInt(match[2]);
  const last = match[1] && match[2] ? BigInt(match[2]) : total - 1n;
  const start = first < 0n ? 0n : first;
  const end = last < total ? last : total - 1n;
  return start <= end ? { start: Number(start), end: Number(end) } : null;
}

async function serve(directory, name, req, res) {
  let file;
  let stage = 'open';
  try {
    file = await fs.promises.open(path.join(directory, name), fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
    stage = 'stat';
    const stat = await file.stat({ bigint: true });
    if (!stat.isFile()) { res.writeHead(404).end(); return; }
    const size = Number(stat.size);
    const modified = new Date(Number(stat.mtimeMs)).toUTCString();
    const etag = '"' + stat.mtimeNs.toString(16) + '-' + stat.size.toString(16) + '"';
    const isApk = name.endsWith('.apk');
    const headers = { 'Content-Type': mediaType(name), 'Last-Modified': modified, ETag: etag };
    if (name === 'update.json') headers['Cache-Control'] = 'no-store';
    if (isApk) headers['Accept-Ranges'] = 'bytes';
    const tags = req.headers['if-none-match'];
    const unmodified = tags
      ? tags.split(',').some(tag => tag.trim() === '*' || tag.trim().replace(/^W\//, '') === etag)
      : Date.parse(modified) <= Date.parse(req.headers['if-modified-since']);
    if (unmodified) { res.writeHead(304, headers).end(); return; }
    let range;
    const validator = req.headers['if-range'];
    if (isApk && req.headers.range && (!validator || validator === etag || validator === modified)) {
      range = byteRange(req.headers.range, size);
      if (!range) {
        res.writeHead(416, { ...headers, 'Content-Range': 'bytes */' + size, 'Content-Length': 0 }).end();
        return;
      }
      headers['Content-Range'] = 'bytes ' + range.start + '-' + range.end + '/' + size;
    }
    headers['Content-Length'] = range ? range.end - range.start + 1 : size;
    if (res.destroyed) return;
    res.writeHead(range ? 206 : 200, headers);
    if (req.method === 'HEAD' || !size) { res.end(); return; }
    stage = 'stream';
    // Use the opened inode for both headers and bytes, even during an atomic publication.
    const stream = file.createReadStream(range || {});
    file = undefined; // the stream owns the descriptor, including on disconnect
    await pipeline(stream, res);
  } catch (error) {
    if (stage === 'open' && ['ENOENT', 'ENOTDIR', 'ELOOP'].includes(error.code)) {
      if (!res.destroyed) res.writeHead(404).end();
      return;
    }
    if (stage === 'stream' && req.destroyed && ['ERR_STREAM_PREMATURE_CLOSE','ECONNRESET'].includes(error.code)) return;
    console.error('[relay] local update transfer failed', { stage, name, error });
    if (!res.destroyed && !res.headersSent) res.writeHead(500).end();
    else if (!res.destroyed) res.destroy(error);
  } finally {
    if (file) await file.close().catch(error => console.error('[relay] close update file failed', { name, error }));
  }
}

function createLocalUpdates(directory, { legacy = false } = {}) {
  if (!path.isAbsolute(directory)) throw new Error('REMODEX_UPDATE_DIR must be an absolute path');
  return (req, res, pathname) => {
    const prefix = legacy ? '/' : '/updates/';
    if (!pathname.startsWith(prefix) || !['GET', 'HEAD'].includes(req.method)) return false;
    const name = pathname.slice(prefix.length) || (legacy ? 'index.html' : '');
    if (!manifestOrApk.test(name) && !(legacy && legacyArtifact.test(name))) return false;
    void serve(directory, name, req, res);
    return true;
  };
}
module.exports = { createLocalUpdates };
