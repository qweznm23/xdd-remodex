// Tickets and keys are delivered only over the paired E2EE RPC connection.
// HTTP carries bounded, independently authenticated binary records, never plaintext files.
const http = require('node:http');
const fs = require('node:fs/promises');
const { randomBytes, createCipheriv, createHash } = require('node:crypto');
const { once } = require('node:events');
const CHUNK = 256 * 1024;
const revisionOf = s => createHash('sha256').update([s.dev, s.ino, s.size, s.mtimeMs, s.ctimeMs].join(':')).digest('hex');
function failure(code, message, status = 409) { return Object.assign(new Error(message), { code, status }); }
function createFileStreams({ now = Date.now } = {}) {
  const tickets = new Map();
  let active = 0;
  function prune() {
    for (const [id, t] of tickets) if (t.expiresAt <= now()) tickets.delete(id);
  }
  function grant({ path, revision, byteLength, data }) {
    prune();
    while (tickets.size >= 64) {
      const oldest = [...tickets].find(([,t]) => !t.active);
      if (!oldest) break;
      tickets.delete(oldest[0]);
    }
    if (tickets.size >= 64 || [...tickets.values()].reduce((n,t) => n + (t.data?.length || 0), 0) + (data?.length || 0) > 64 * 1024 * 1024) {
      throw failure('transfer_busy', 'Too many pending file transfers', 429);
    }
    const token = randomBytes(32).toString('hex'), key = randomBytes(32), expiresAt = now() + 30 * 60_000;
    tickets.set(token, { path, revision, byteLength, data, key, expiresAt });
    return { protocol: 'aesgcm-records-v1', rangeSupported: true, token, keyBase64: key.toString('base64'), byteLength, revision, expiresAt };
  }
  async function handle(req, res) {
    let file, primary;
    try {
      prune();
      const url = new URL(req.url, 'http://localhost');
      if (req.method !== 'GET' || url.pathname !== '/files/stream') throw failure('not_found', 'Not found', 404);
      const token = String(req.headers.authorization || '').replace(/^Bearer /, '');
      const ticket = tickets.get(token);
      if (!ticket) throw failure('transfer_expired', 'File transfer expired; retry the download', 401);
      if (active >= 4) throw failure('transfer_busy', 'File transfer capacity reached', 429);
      const offset = Number(url.searchParams.get('offset') || 0);
      if (!Number.isSafeInteger(offset) || offset < 0 || offset > ticket.byteLength) throw failure('invalid_offset', 'Invalid file offset', 416);
      const rawLength = url.searchParams.get('length');
      const length = rawLength == null ? null : Number(rawLength);
      if (length != null && (!Number.isSafeInteger(length) || length <= 0 || length > 4 * 1024 * 1024)) throw failure('invalid_length', 'Invalid file range length', 416);
      const end = length == null ? ticket.byteLength : Math.min(ticket.byteLength, offset + length);
      active++; ticket.active = (ticket.active || 0) + 1;
      try {
        file = await fs.open(ticket.path, require('node:fs').constants.O_RDONLY | (require('node:fs').constants.O_NOFOLLOW || 0));
        async function validate() {
          const stat = await file.stat();
          if (!stat.isFile() || revisionOf(stat) !== ticket.revision) throw failure('file_changed', 'File changed during transfer');
        }
        await validate();
        res.writeHead(200, { 'Content-Type': 'application/vnd.remodex.encrypted-stream', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
        let position = offset;
        async function record(bytes) {
          const nonce = randomBytes(12);
          const cipher = createCipheriv('aes-256-gcm', ticket.key, nonce);
          cipher.setAAD(Buffer.from(`${token}:${ticket.revision}:${position}`));
          const encrypted = Buffer.concat([cipher.update(bytes), cipher.final(), cipher.getAuthTag()]);
          const size = Buffer.alloc(4); size.writeUInt32BE(bytes.length);
          if (!res.write(Buffer.concat([size, nonce, encrypted]))) await once(res, 'drain');
        }
        while (position < end) {
          if (res.destroyed) throw failure('client_disconnected', 'Download connection closed');
          const count = Math.min(CHUNK, end - position);
          let bytes;
          if (ticket.data) bytes = ticket.data.subarray(position, position + count);
          else {
            bytes = Buffer.alloc(count); let read = 0;
            while (read < count) {
              const result = await file.read(bytes, read, count - read, position + read);
              if (!result.bytesRead) throw failure('file_changed', 'File ended during transfer');
              read += result.bytesRead;
            }
          }
          await record(bytes); position += bytes.length;
        }
        await validate();
        await record(Buffer.alloc(0)); // Authenticated EOF; a truncated response cannot look successful.
        res.end();
      } finally { active--; ticket.active--; if (length == null) ticket.expiresAt = Math.min(ticket.expiresAt, now() + 120_000); }
    } catch (error) {
      primary = error;
      // Never log the bearer token, URL query, or key.
      console.error('[file-stream]', {stage:'stream', code:error.code || 'unknown', name:error.name, message:error.message, stack:error.stack});
      if (!res.headersSent) {
        res.writeHead(error.status || 500, {'Content-Type':'application/json', 'Cache-Control':'no-store'});
        res.end(JSON.stringify({code:error.code || 'unknown', stage:'stream', message:error.message}));
      } else res.destroy(error);
    } finally {
      if (file) try { await file.close(); } catch (error) {
        if (primary) (primary.suppressed ||= []).push(error);
        console.error('[file-stream] cleanup', {stage:'close', message:error.message, stack:error.stack, primaryCode:primary?.code});
      }
    }
  }
  const server = http.createServer((req,res) => {
    // Reject the backpressure wait promptly when the phone cancels a transfer.
    res.on('close', () => { if (!res.writableFinished) res.emit('error', failure('client_disconnected','Download connection closed')); });
    res.on('error', () => {});
    void handle(req,res);
  });
  server.requestTimeout = 0;
  return {server, grant, close: () => { tickets.clear(); server.closeAllConnections(); return new Promise(resolve => server.close(resolve)); }};
}
module.exports = {createFileStreams, revisionOf};
