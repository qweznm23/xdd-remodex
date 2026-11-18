const http = require('http');
const https = require('https');

// Optional, read-only update distribution on the relay's existing HTTP port.
function createUpdateDownloads(origin) {
  if (!origin) return () => false;
  const upstream = new URL(origin);
  if (!['http:', 'https:'].includes(upstream.protocol) || upstream.username || upstream.password || upstream.search || upstream.hash || upstream.pathname !== '/') {
    throw new Error('Update origin must be an HTTP(S) origin without credentials or path');
  }
  return (req, res, pathname) => {
    if (!['GET', 'HEAD'].includes(req.method) || !/^\/updates\/(update\.json|remodex-[A-Za-z0-9._-]+\.apk)$/.test(pathname)) return false;
    const target = new URL(pathname.slice('/updates'.length), upstream);
    const headers = {};
    for (const key of ['range', 'if-range', 'if-none-match', 'if-modified-since']) {
      if (req.headers[key]) headers[key] = req.headers[key];
    }
    const request = (upstream.protocol === 'https:' ? https : http).request(target, {method: req.method, headers}, response => {
      const forwarded = {};
      for (const key of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'etag', 'last-modified', 'cache-control']) {
        if (response.headers[key] != null) forwarded[key] = response.headers[key];
      }
      res.writeHead(response.statusCode, forwarded);
      response.on('error', error => fail('read', error));
      response.pipe(res);
    });
    let failed = false;
    function fail(stage, error) {
      if (failed || res.destroyed) return;
      failed = true;
      console.error('[relay] update transfer failed', {stage, code: error.code, message: error.message, stack: error.stack});
      if (!res.headersSent) {
        const body = JSON.stringify({error:'Update upstream unavailable', stage, code:error.code || 'unknown'});
        res.writeHead(502, {'Content-Type':'application/json', 'Content-Length':Buffer.byteLength(body)});
        res.end(body);
      } else res.destroy(error);
    }
    request.on('error', error => fail('upstream', error));
    request.setTimeout(20_000, () => request.destroy(Object.assign(new Error('Update upstream idle timeout'), {code:'ETIMEDOUT'})));
    res.on('close', () => request.destroy());
    request.end();
    return true;
  };
}
module.exports = {createUpdateDownloads};
