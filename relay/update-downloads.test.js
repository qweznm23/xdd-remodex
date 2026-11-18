const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {createRelayServer} = require('./server');

test('relay exposes only update manifest and APK, preserving range and relative URLs', async () => {
  const requests = [];
  const upstream = http.createServer((req, res) => {
    requests.push({url:req.url, range:req.headers.range});
    if (req.url === '/update.json') {
      res.writeHead(200, {'Content-Type':'application/json','Cache-Control':'no-store'});
      res.end(JSON.stringify({apkUrl:'remodex-test.apk'}));
    } else {
      res.writeHead(206, {'Content-Range':'bytes 0-2/6','Content-Length':'3','Accept-Ranges':'bytes'});
      res.end('apk');
    }
  });
  await new Promise(r => upstream.listen(0, '127.0.0.1', r));
  const {server, wss} = createRelayServer({updateOrigin:`http://127.0.0.1:${upstream.address().port}`});
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const manifest = await fetch(base+'/updates/update.json');
    assert.equal(manifest.headers.get('cache-control'),'no-store');
    const apk = await fetch(new URL((await manifest.json()).apkUrl, manifest.url), {headers:{Range:'bytes=0-2'}});
    assert.equal(apk.status,206);
    assert.equal(apk.headers.get('content-range'),'bytes 0-2/6');
    assert.equal(await apk.text(),'apk');
    for (const path of ['/updates/imes.apk','/updates/secret.txt','/updates/%2e%2e/state','/updates/update.json/extra']) {
      assert.equal((await fetch(base+path)).status,404);
    }
    assert.deepEqual(requests,[{url:'/update.json',range:undefined},{url:'/remodex-test.apk',range:'bytes=0-2'}]);
  } finally { wss.close(); server.closeAllConnections(); upstream.closeAllConnections(); await Promise.all([new Promise(r=>server.close(r)),new Promise(r=>upstream.close(r))]); }
});
