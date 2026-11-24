const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { setTimeout: sleep } = require('node:timers/promises');
const { createCodexTransport } = require('../src/codex-transport');
const { manageAuthProfiles } = require('../src/auth-profiles');

test('owned macOS route switches and restarts the matching stdio child, preserving account evidence', async t => {
  const home = await fs.mkdtemp(path.join(os.tmpdir(), 'remodex-owned-test-'));
  t.after(() => fs.rm(home, { recursive:true, force:true }));
  const authPath = path.join(home, 'auth.json');
  const credential = id => ({auth_mode:'chatgpt', tokens:{access_token:'header.'+Buffer.from(JSON.stringify({iat:1700000000,exp:2090000000})).toString('base64url')+'.private-access-'+id, refresh_token:'private-refresh-'+id, account_id:'synthetic-account-'+id}});
  for (const id of ['1','2']) {
    const dir = path.join(home,'auth-profiles',id);
    await fs.mkdir(dir,{recursive:true});
    await fs.writeFile(path.join(dir,'auth.json'),JSON.stringify(credential(id)));
  }
  await fs.copyFile(path.join(home,'auth-profiles/1/auth.json'),authPath);
  const transport = createCodexTransport({platform:'darwin', env:{...process.env,CODEX_HOME:home},
    spawnImpl: (_cmd,_args,opts) => spawn(process.execPath,[path.join(__dirname,'fixtures/owned-auth-child.cjs')],opts)});
  t.after(() => transport.shutdown());
  await new Promise(resolve=>transport.onStarted(resolve));
  let id = 0;
  const waiters = new Map();
  transport.onMessage(raw => {
    const msg=JSON.parse(raw);
    waiters.get(msg.id)?.(msg.result);
    waiters.delete(msg.id);
  });
  const control = (method,params) => {
    if (method==='child/identity') return transport.childIdentity();
    if (method==='child/restart') return transport.restartOwned(params.expected);
    return new Promise(resolve => {
      waiters.set(++id,resolve);
      transport.send(JSON.stringify({id,method,params}));
    });
  };
  const file={status:'present',storage:'file',path:authPath};
  const call = (action,params={})=>manageAuthProfiles(action,params,file,{ownedControl:control});
  async function complete(op) {
    const until=Date.now()+18000;
    while (Date.now()<until) {
      const record=(await call('restart-progress')).restartHistory[0];
      assert.equal(record.operationId,op.operationId);
      if (!['running','restart_scheduled'].includes(record.status)) {
        assert.equal(record.status,'ready',JSON.stringify(record.failure));
        assert.ok(record.steps.some(s=>s.stage==='runtime_account_verification'));
        return;
      }
      await sleep(150);
    }
    assert.fail('worker did not finish');
  }
  const listing=await call('list');
  assert.equal(listing.runtimeControl.owner,'bridge');
  assert.equal(listing.runtimeControl.action,'restart');
  const first=transport.childIdentity();
  const rejected=await call('restart',{expectedRuntime:'0'.repeat(64)});
  assert.equal(rejected.failure.code,'RESTART_SELECTION_CHANGED');
  assert.equal(transport.childIdentity(),first);
  const switched=await call('switch',{profile:'2',revision:listing.profiles[0].revision});
  assert.equal(switched.restartAfterSeconds,3);
  assert.equal(JSON.parse(await fs.readFile(authPath)).tokens.account_id,'synthetic-account-1');
  await complete(switched);
  assert.notEqual(transport.childIdentity(),first);
  const second=transport.childIdentity();
  assert.equal((await control('getAuthStatus',{})).authToken,credential('2').tokens.access_token);
  const usage = await call('runtime-quota');
  assert.equal(usage.profile, '2');
  assert.equal(usage.quota.rateLimits.primary.usedPercent, 30);
  const restart=await call('restart',{expectedRuntime:(await call('list')).runtimeControl.listenerConfirmation});
  await complete(restart);
  assert.notEqual(transport.childIdentity(),second);
  const history=JSON.stringify(await call('restart-progress'));
  assert.ok(!history.includes('private-access') && !history.includes('private-refresh'));
});

test('shared Mac Unix transport reopens after an acknowledged restart', {timeout:10000}, async t => {
  const http = require('node:http');
  const { once } = require('node:events');
  const { WebSocketServer } = require('ws');
  const directory = await fs.mkdtemp(path.join(os.tmpdir(),'remodex-owned-test-'));
  const endpoint = path.join(directory,'c.sock');
  const server = http.createServer();
  const sockets = new WebSocketServer({server});
  server.listen(endpoint);
  await once(server,'listening');
  const transport = createCodexTransport({platform:'darwin',endpoint:'ws+unix://'+endpoint+':/'});
  t.after(async () => {
    transport.shutdown();
    for (const c of sockets.clients) c.terminate();
    sockets.close(); server.close(); await fs.rm(directory,{recursive:true,force:true});
  });
  assert.equal((await new Promise(resolve=>transport.onStarted(resolve))).reconnected,false);
  transport.expectRestart();
  let interruptions=0;
  transport.onRestarting(()=>{ interruptions++; });
  const connected = new Promise((resolve,reject)=>{ transport.onStarted(resolve); transport.onError(reject); transport.onClose(reject); });
  for (const ws of sockets.clients) ws.terminate();
  assert.equal((await connected).reconnected,true);
  assert.equal(interruptions,1);
  const closed = new Promise(resolve=>transport.onClose(resolve));
  for (const ws of sockets.clients) ws.terminate();
  await closed;
  assert.equal(interruptions,1);
});
