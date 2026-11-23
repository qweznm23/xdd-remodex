const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const {createRelayServer} = require('./server');
const {createLocalUpdates} = require('./local-updates');

test('both update addresses serve the same release, validators, atomic replacements and single ranges', async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'remodex-updates-'));
  t.after(() => fs.rm(directory, {recursive:true, force:true}));
  const name = 'remodex-test.apk';
  const artifact = path.join(directory, name);
  await fs.writeFile(artifact, 'abcdef');
  await fs.writeFile(path.join(directory,'update.json'), JSON.stringify({apkUrl:name}));
  await fs.writeFile(path.join(directory,'index.html'), 'index');
  await fs.writeFile(path.join(directory,'private.txt'), 'secret');
  await fs.symlink('private.txt', path.join(directory,'remodex-link.apk'));
  const {server, wss} = createRelayServer({updateDirectory:directory, updateOrigin:'http://127.0.0.1:1'});
  t.after(() => wss.close());
  const handleLegacy = createLocalUpdates(directory, {legacy:true});
  const legacy = http.createServer((req,res) => {
    if (!handleLegacy(req,res,new URL(req.url,'http://localhost').pathname)) res.writeHead(404).end();
  });
  for (const listener of [server,legacy]) {
    await new Promise(resolve => listener.listen(0,'127.0.0.1',resolve));
    t.after(() => new Promise(resolve => { listener.close(resolve); listener.closeAllConnections(); }));
  }
  for (const [listener, prefix] of [[server,'/updates/'],[legacy,'/']]) {
    const base = 'http://127.0.0.1:' + listener.address().port + prefix;
    const manifest = await fetch(base+'update.json?check=1');
    assert.equal(manifest.headers.get('cache-control'),'no-store');
    const url = new URL((await manifest.json()).apkUrl, manifest.url);
    await fs.writeFile(artifact, 'abcdef');
    const full = await fetch(url);
    assert.equal(await full.text(),'abcdef');
    const etag = full.headers.get('etag');
    const lastModified = full.headers.get('last-modified');
    for (const [range, bytes] of [['bytes=2-','cdef'],['bytes=-2','ef'],['bytes=1-3','bcd'],['bytes=0-99','abcdef']]) {
      const r = await fetch(url,{headers:{range,'If-Range':etag}});
      assert.equal(r.status,206);
      assert.equal(await r.text(),bytes);
    }
    const head = await fetch(url,{method:'HEAD',headers:{Range:'bytes=2-','If-Range':lastModified}});
    assert.equal(head.headers.get('content-range'),'bytes 2-5/6');
    assert.equal(head.headers.get('content-length'),'4');
    assert.equal(await head.text(),'');
    assert.equal((await fetch(url,{headers:{'If-None-Match':etag}})).status,304);
    for (const range of ['bytes=-0','bytes=8-','bytes=3-2','bytes=1-2,4-5','bytes=-']) {
      const invalid = await fetch(url,{headers:{range}});
      assert.equal(invalid.status,416);
      assert.equal(invalid.headers.get('content-range'),'bytes */6');
    }
    await fs.writeFile(artifact+'.tmp','replaced');
    await fs.rename(artifact+'.tmp',artifact);
    const changed = await fetch(url,{headers:{range:'bytes=2-','If-Range':etag}});
    assert.equal(changed.status,200);
    assert.equal(await changed.text(),'replaced');
    for (const file of ['private.txt','remodex-link.apk','remodex-absent.apk','%2fprivate.txt','private/remodex-test.apk']) {
      assert.equal((await fetch(base+file)).status,404);
    }
    assert.equal((await fetch(url,{method:'POST'})).status,404);
    assert.equal((await fetch(base+'index.html')).status,listener===legacy?200:404);
  }
  assert.equal((await fetch('http://127.0.0.1:'+legacy.address().port+'/health')).status,404);
});
