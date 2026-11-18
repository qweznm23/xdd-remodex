const http = require('node:http');
// Only a loopback bridge is reachable from this opt-in route. No client-selected target/path.
function createFileDownloads(origin) {
  if (!origin) return () => false;
  const upstream = new URL(origin);
  if (upstream.protocol !== 'http:' || !['127.0.0.1','[::1]','localhost'].includes(upstream.hostname) || upstream.pathname !== '/' || upstream.username || upstream.password || upstream.search || upstream.hash) throw new Error('File origin must be a loopback HTTP origin');
  return (req,res,pathname) => {
    if (pathname !== '/files/stream' || req.method !== 'GET') return false;
    const params = new URL(req.url,'http://localhost').searchParams;
    const offset = params.get('offset') || '0';
    const length = params.get('length');
    if (length != null && (!/^\d{1,7}$/.test(length) || Number(length) < 1 || Number(length) > 4 * 1024 * 1024)) { res.writeHead(416); res.end(); return true; }
    if (!/^\d{1,16}$/.test(offset)) { res.writeHead(400); res.end(); return true; }
    const target = new URL('/files/stream?offset='+offset,upstream);
    if (length != null) target.searchParams.set('length',length);
    const request = http.get(target,{headers:{authorization:req.headers.authorization || ''}},response => {
      res.writeHead(response.statusCode,{'Content-Type':response.headers['content-type'] || 'application/octet-stream','Cache-Control':'no-store'});
      response.on('error',error => res.destroy(error));
      response.pipe(res);
    });
    request.on('error',error => {
      console.error('[file-proxy]',{stage:'upstream',code:error.code,message:error.message,stack:error.stack});
      if (!res.headersSent) { res.writeHead(502,{'Content-Type':'application/json'}); res.end(JSON.stringify({stage:'file_proxy',code:error.code || 'unknown',message:'File stream upstream unavailable'})); }
      else res.destroy(error);
    });
    request.setTimeout(30_000,()=>request.destroy(Object.assign(new Error('File upstream idle timeout'),{code:'ETIMEDOUT'})));
    res.on('close',()=>request.destroy());
    return true;
  };
}
module.exports={createFileDownloads};
