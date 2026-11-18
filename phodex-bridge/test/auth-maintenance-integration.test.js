const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { createHash } = require('node:crypto');
const { manageAuthProfiles } = require('../src/auth-profiles');
const { createAuthMaintenance } = require('../src/auth-maintenance');
const { createWorkspaceUploadHandler } = require('../src/workspace-upload');
const { handleWorkspaceRequest } = require('../src/workspace-handler');

function credential(id, date = '2026-01-01T00:00:00Z') {
  const claims = Buffer.from(JSON.stringify({ iat: Date.parse(date)/1000, exp: 4102444800 })).toString('base64url');
  return JSON.stringify({auth_mode: 'chatgpt', last_refresh: date,
    tokens: {account_id: id, access_token: 'header.'+claims+'.signature', refresh_token: 'synthetic-'+id+'-'+date}});
}
test('normal service entry handles imports, checks, queries, refresh, exports, switching and schedule recovery', async t => {
  const home = await fs.mkdtemp(path.join(os.tmpdir(), 'remodex-auth-service-'));
  const oldEnv = {...process.env};
  const save = async (file, contents) => {
    await fs.mkdir(path.dirname(file), {recursive: true, mode: 0o700});
    await fs.writeFile(file, contents, {mode: 0o600});
  };
  await save(path.join(home,'.fixture'), '');
  const bin = path.join(home,'bin');
  await save(path.join(bin,'systemd-run'), `#!/usr/bin/python3
import json, os, subprocess, sys
from pathlib import Path
root = Path(os.environ['REMODEX_AUTH_FIXTURE_ROOT'])
assert (root/'.fixture').exists()
if (root/'reject-schedule').exists():
    print('fixture scheduler rejected', file=sys.stderr)
    raise SystemExit(42)
args = sys.argv[sys.argv.index('/usr/bin/python3'):]
(root/'scheduled.json').write_text(json.dumps(args))
subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
`);
  await fs.chmod(path.join(bin,'systemd-run'),0o700);
  process.env.REMODEX_AUTH_CODEX_BIN = path.join(__dirname,'fixtures/auth-codex.py');
  process.env.REMODEX_AUTH_FIXTURE_ROOT = home;
  process.env.PATH = bin+path.delimiter+process.env.PATH;
  t.after(async () => {
    process.env = oldEnv;
    await fs.rm(home,{recursive:true,force:true});
  });
  const current = path.join(home,'auth.json');
  const one = path.join(home,'auth-profiles/1/auth.json');
  const two = path.join(home,'auth-profiles/2/auth.json');
  await save(current,credential('active','2026-02-01T00:00:00Z'));
  await save(one,credential('standby'));
  await save(two,credential('active'));
  const file = {status:'present',storage:'file',path:current};
  const call = (action,params={}) => manageAuthProfiles(action,params,file);
  const get = async id => (await call('status')).profiles.find(p=>p.id===id);
  const selection = row => ({profile:row.id,revision:row.revision});
  assert.deepEqual((await call('list')).profiles.map(p=>p.id),['1']);
  const initial = await get('1');
  assert.equal((await call('check',selection(initial))).credentialValidity,'not_verified');
  assert.equal((await call('query',selection(initial))).email,'fixture@example.test');
  await assert.rejects(fs.access(path.join(home,'refresh-calls')));

  // Upload through the same protected upload implementation, then reference the completed path.
  const upload = createWorkspaceUploadHandler({root:path.join(home,'remodex_uploads')});
  const payload = Buffer.from(credential('third'));
  const sha256 = createHash('sha256').update(payload).digest('hex');
  const session = await upload('workspace/uploadBegin',{fileName:'auth.json',byteLength:payload.length});
  await upload('workspace/uploadChunk',{uploadId:session.uploadId,offset:0,dataBase64:payload.toString('base64'),sha256});
  const uploaded = await upload('workspace/uploadFinish',{uploadId:session.uploadId,sha256,byteLength:payload.length});
  const imported = await call('import',{profile:'3',revision:'missing',sourcePath:uploaded.path});
  assert.equal(imported.profileInfo.id,'3');
  await assert.rejects(call('import',{profile:'3',revision:'missing',sourcePath:uploaded.path}),e=>e.errorCode==='PROFILE_CHANGED');
  await assert.rejects(call('import',{profile:'3',revision:imported.profileInfo.revision,sourcePath:current}),e=>e.errorCode==='IMPORT_SOURCE_NOT_ALLOWED');
  const replacement = credential('third-replaced');
  await save(uploaded.path, replacement);
  await call('import',{profile:'3',revision:imported.profileInfo.revision,sourcePath:uploaded.path});
  assert.equal(await fs.readFile(path.join(home,'auth-profiles/3/auth.json'),'utf8'),replacement);
  const backups = await fs.readdir(path.join(home,'auth-profiles/maintenance/imports'));
  assert.equal(backups.length,1);
  assert.equal(await fs.readFile(path.join(home,'auth-profiles/maintenance/imports',backups[0],'auth.json'),'utf8'),payload.toString());

  const activeBefore = await fs.readFile(current,'utf8');
  const refreshed = await call('refreshToken',selection(await get('1')));
  assert.equal(refreshed.status,'refreshed');
  assert.equal(await fs.readFile(current,'utf8'),activeBefore);
  assert.ok(!(JSON.stringify(refreshed).includes('synthetic-')));
  const exported = await call('export',selection(await get('1')));
  assert.equal((await fs.stat(exported.path)).mode & 0o777,0o600);
  const workspace = (method, params) => new Promise(resolve => {
    assert.equal(handleWorkspaceRequest(JSON.stringify({id:'download',method,params}), raw=>resolve(JSON.parse(raw))),true);
  });
  const info = await workspace('workspace/fileInfo',{cwd:exported.cwd,path:exported.path});
  assert.ok(info.result,JSON.stringify(info.error));
  const response = await workspace('workspace/readFileChunk',{cwd:exported.cwd,path:exported.path,offset:0,revision:info.result.revision});
  assert.ok(response.result,JSON.stringify(response.error));
  assert.equal(Buffer.from(response.result.dataBase64, 'base64').toString(), await fs.readFile(one, 'utf8'));
  assert.equal(await fs.readFile(exported.path,'utf8'),await fs.readFile(one,'utf8'));

  // Force conflict with a second process holding the same host lock.
  const {spawn} = require('node:child_process');
  const lock = spawn('flock',['-F',path.join(home,'auth-profiles/maintenance/manager.lock'),'sleep','3']);
  await new Promise(r=>setTimeout(r,100));
  await assert.rejects(call('refreshToken',selection(initial)),e=>e.errorCode==='MANAGER_BUSY');
  lock.kill();
  await new Promise(r=>lock.once('exit',r));

  // A daily tick and a restarted scheduler cannot rotate a recently refreshed account again.
  let scheduler = createAuthMaintenance({resolveAuthFile:async()=>file,enabled:true});
  await scheduler.tick(); scheduler.stop();
  let heartbeat = JSON.parse(await fs.readFile(path.join(home,'auth-profiles/maintenance/remodex-scheduler.json'),'utf8'));
  assert.equal(heartbeat.owner,'remodex');
  const calls = await fs.readFile(path.join(home,'refresh-calls'),'utf8');
  assert.ok(!calls.includes('active'));
  scheduler = createAuthMaintenance({resolveAuthFile:async()=>file,enabled:true});
  await scheduler.tick(); scheduler.stop();
  assert.equal(await fs.readFile(path.join(home,'refresh-calls'),'utf8'),calls);
  // Current-account synchronization also runs before the daily inactive job.
  assert.equal(await fs.readFile(two,'utf8'),activeBefore);

  await save(path.join(home,'reject-schedule'),'1');
  await assert.rejects(call('switch',selection(await get('1'))),e=>e.message.includes('42'));
  assert.equal(await fs.readFile(current,'utf8'),activeBefore);
  await fs.unlink(path.join(home,'reject-schedule'));
  const switched = await call('switch',selection(await get('1')));
  assert.equal(switched.status,'restart_scheduled');
  for(let i=0;i<40;i++) {
    try {
      const result=await call('list');
      if(result.restartStatus==='restarted') break;
    } catch(error) {
      if(error.errorCode !== 'MANAGER_BUSY') throw error;
    }
    await new Promise(r=>setTimeout(r,50));
  }
  assert.equal((await call('list')).restartStatus,'restarted');
  const scheduled = JSON.parse(await fs.readFile(path.join(home,'scheduled.json'),'utf8'));
  assert.ok(scheduled.some(s=>s.endsWith('/auth_store.py')));
  assert.ok(!scheduled.some(s=>s.includes('.local/lib/codex-auth-manager')));
  assert.equal(JSON.parse(await fs.readFile(current,'utf8')).tokens.account_id,'standby');
});

test('active fallback uses the connected control socket and persists matching files without repeat rotation', async t => {
  const { spawn } = require('node:child_process');
  const { once } = require('node:events');
  const home = await fs.mkdtemp(path.join(os.tmpdir(), 'remodex-auth-runtime-'));
  await fs.writeFile(path.join(home, '.fixture'), '');
  const now = Date.now();
  const old = JSON.parse(credential('active', new Date(now - 3600000).toISOString()));
  const claims = Buffer.from(JSON.stringify({iat: now/1000 - 3600, exp: now/1000 + 300})).toString('base64url');
  old.tokens.access_token = 'header.' + claims + '.signature';
  await fs.writeFile(path.join(home, 'auth.json'), JSON.stringify(old), {mode: 0o600});
  const saved = path.join(home, 'auth-profiles/1/auth.json');
  await fs.mkdir(path.dirname(saved), {recursive: true, mode: 0o700});
  await fs.writeFile(saved, credential('active', new Date(now - 7200000).toISOString()), {mode: 0o600});
  const child = spawn('/usr/bin/python3', [path.join(__dirname, 'fixtures/auth-runtime.py'), home]);
  let stderr = '';
  child.stderr.on('data', chunk => { stderr += chunk; });
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) {
      child.kill(); await once(child, 'exit');
    }
    await fs.rm(home, {recursive: true, force: true});
  });
  await Promise.race([
    once(child.stdout, 'data'),
    once(child, 'exit').then(() => { throw new Error('Fixture stopped: ' + stderr); }),
  ]);
  const file = {status: 'present', storage: 'file', path: path.join(home, 'auth.json')};
  const options = {endpoint: 'ws+unix://' + path.join(home, 'control.sock') + ':/'};
  const first = await manageAuthProfiles('maintenance', {}, file, options);
  assert.equal(first.activeMaintenance.status, 'refreshed');
  assert.equal(await fs.readFile(saved, 'utf8'), await fs.readFile(file.path, 'utf8'));
  assert.ok(!JSON.stringify(first).includes('synthetic-renewed-refresh'));
  const recovered = await manageAuthProfiles('maintenance', {}, file, options);
  assert.equal(recovered.activeMaintenance.status, 'healthy');
  assert.equal(await fs.readFile(path.join(home, 'refresh-calls'), 'utf8'), 'refresh\n');
});
