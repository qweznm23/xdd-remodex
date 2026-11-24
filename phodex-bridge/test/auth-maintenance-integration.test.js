const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { createHash } = require('node:crypto');
const { execFileSync, spawn } = require('node:child_process');
const { once } = require('node:events');
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
  const endpoint = 'ws+unix://' + path.join(home, 'app-server-control/app-server-control.sock') + ':/';
  const call = (action,params={}) => manageAuthProfiles(action,params,file,{ endpoint });
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
  const fourth = (await call('status')).nextProfileId;
  assert.equal(fourth, '4');
  const named = JSON.parse(credential('fourth'));
  named.tokens.id_token = 'h.' + Buffer.from(JSON.stringify({name:'Fixture User',email:'four@example.test'})).toString('base64url') + '.s';
  await save(uploaded.path, JSON.stringify(named));
  const add = {profile:fourth, revision:'missing', sourcePath:uploaded.path, displayName:'工作'};
  const added = (await call('import', add)).profileInfo;
  assert.equal(added.displayName,'工作');
  assert.equal(added.accountName,'Fixture User');
  assert.equal(added.email,'four@example.test');
  assert.ok(!JSON.stringify(added).includes(named.tokens.id_token));
  await assert.rejects(call('import', add), e=>e.errorCode==='PROFILE_CHANGED');
  await call('rename',{...selection(added),displayName:'备用',expectedName:'工作'});
  await assert.rejects(call('rename',{...selection(added),displayName:'stale',expectedName:'工作'}),
    e=>e.errorCode==='PROFILE_CHANGED' && e.profileDiagnostic.stage==='rename_profile');
  assert.equal((await get(fourth)).displayName,'备用');
  const unchanged = await fs.readFile(path.join(home,'auth-profiles',fourth,'auth.json'),'utf8');
  assert.equal(unchanged, JSON.stringify(named));
  await assert.rejects(call('remove',selection(await get('2'))),e=>e.errorCode==='PROFILE_ACTIVE');
  await call('remove',selection(added));
  assert.equal(await get(fourth),undefined);
  assert.equal((await call('status')).nextProfileId,'5');
  await assert.rejects(call('import',add),e=>e.errorCode==='PROFILE_CHANGED');
  assert.equal(await fs.readFile(path.join(home,'auth-profiles/maintenance/removed',fourth,'auth.json'),'utf8'),unchanged);
  await save(uploaded.path,payload);
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

  await save(path.join(home, 'runtime-auth.json'), activeBefore);
  const child = spawn('/usr/bin/python3', [path.join(__dirname, 'fixtures/auth-runtime.py'), home]);
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) {
      child.kill(); await once(child, 'exit');
    }
  });
  await Promise.race([once(child.stdout, 'data'), once(child, 'exit').then(() => { throw new Error('Restart fixture exited'); })]);
  const liveQuota = await call('runtime-quota');
  assert.equal(liveQuota.profile, '2');
  assert.equal(liveQuota.quota.rateLimits.primary.usedPercent, 68);
  assert.equal(await fs.readFile(path.join(home,'refresh-calls'),'utf8'),calls);
  await assert.rejects(call('adopt', {confirmation: 'invalid'}), /confirmation required/);
  await save(path.join(home,'reject-schedule'),'1');
  const rejected = await call('switch',selection(await get('1')));
  assert.equal(rejected.status, 'failed');
  assert.equal(rejected.failure.exitCode, 42);
  assert.equal(rejected.failure.stage, 'restart_schedule');
  assert.match(rejected.failure.stderr, /fixture scheduler rejected/);
  assert.equal(await fs.readFile(current,'utf8'),activeBefore);
  await fs.unlink(path.join(home,'reject-schedule'));
  const next = (await call('status')).nextProfileId;
  assert.equal(next, '5');
  await call('import',{profile:next,revision:'missing',sourcePath:uploaded.path,displayName:'第五个'});
  async function completed(operationId) {
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      try {
        const record = (await call('list')).restartHistory.find(row => row.operationId === operationId);
        if (record && !['restart_scheduled', 'running'].includes(record.status)) return record;
      } catch(error) {
        if(error.errorCode !== 'MANAGER_BUSY') throw error;
      }
      await new Promise(r=>setTimeout(r,50));
    }
    assert.fail('Restart did not complete: ' + operationId);
  }
  await save(path.join(home, 'unmanaged'), '1');
  const unmanaged = await call('switch',selection(await get(next)));
  assert.equal(unmanaged.failure.code, 'RESTART_RUNTIME_UNMANAGED');
  assert.equal(await fs.readFile(current,'utf8'),activeBefore);
  await fs.unlink(path.join(home, 'unmanaged'));
  await save(path.join(home,'reject-restart'),'1');
  const failed = await call('switch',selection(await get(next)));
  const rolledBack = await completed(failed.operationId);
  assert.equal(rolledBack.failure.exitCode, 18);
  assert.equal(rolledBack.failure.stage, 'daemon_restart');
  assert.equal(rolledBack.recovery.status, 'rolled_back');
  const progress = (await call('restart-progress')).restartHistory[0];
  assert.equal(progress.operationId, failed.operationId);
  assert.ok(progress.steps.find(row => row.stage === 'switch_write' && row.status === 'running'));
  assert.equal(progress.steps.at(-1).status, 'failed');
  assert.equal(await fs.readFile(current,'utf8'),activeBefore);
  await fs.unlink(path.join(home,'reject-restart'));
  const switched = await call('switch',selection(await get(next)));
  assert.equal(switched.status,'restart_scheduled');
  assert.equal(switched.restartAfterSeconds, 3);
  assert.equal((await completed(switched.operationId)).status, 'ready');
  const scheduled = JSON.parse(await fs.readFile(path.join(home,'scheduled.json'),'utf8'));
  assert.ok(scheduled.some(s=>s.endsWith('/auth-profiles.py')));
  assert.ok(!scheduled.some(s=>s.includes('.local/lib/codex-auth-manager')));
  assert.equal(JSON.parse(await fs.readFile(current,'utf8')).tokens.account_id,'third-replaced');
  assert.equal((await get(next)).active,true);
  const authBeforeManual = await fs.readFile(current, 'utf8');
  const manual = await call('restart');
  assert.equal(manual.restartAfterSeconds, 3);
  assert.equal((await completed(manual.operationId)).status, 'ready');
  assert.equal(await fs.readFile(current, 'utf8'), authBeforeManual);
  const history = (await call('list')).restartHistory;
  assert.deepEqual(history.map(row => row.operationId), [manual, switched, failed, unmanaged, rejected].map(row => row.operationId));
  assert.ok(!JSON.stringify(history).includes('synthetic-'));
  await fs.rename(current, path.join(home, 'offline-auth.json'));
  const offline = execFileSync('/usr/bin/python3', [path.join(__dirname, '../src/auth-cli.py'), '--home', home, 'restart-history']);
  assert.deepEqual(JSON.parse(offline).restartHistory, history);
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
