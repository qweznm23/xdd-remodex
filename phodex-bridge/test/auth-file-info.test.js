const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { readAuthFileInfo, authFileLocation, authFileFailure, MAX_AUTH_BYTES } = require('../src/auth-file-info');
const config = (home, store = 'file') => ({ config: { cli_auth_credentials_store: store }, layers: [{ name: { type: 'user', file: path.join(home, 'config.toml') } }] });
async function fixture(t) { const home = await fs.mkdtemp(path.join(os.tmpdir(), 'remodex-auth-info-')); t.after(() => fs.rm(home, { recursive: true, force: true })); return home; }

test('uses the active runtime config home, not bridge default, and exports only metadata', async t => {
 const home = await fixture(t);
 await fs.writeFile(path.join(home, 'auth.json'), JSON.stringify({ auth_mode: 'chatgpt', OPENAI_API_KEY: 'private-key', tokens: { access_token: 'private-access', refresh_token: 'private-refresh', id_token: 'private-id' }, last_refresh: '2026-11-07T03:53:09.902985394Z' }));
 const result = await readAuthFileInfo({ configResult: config(home), endpoint: 'ws+unix:///runtime.sock:/', spawnedHome: '/wrong' });
 assert.equal(result.path, path.join(home, 'auth.json')); assert.equal(result.status, 'present'); assert.equal(result.authMode, 'chatgpt'); assert.equal(result.lastRefresh, '2026-11-07T03:53:09.902Z');
 assert.ok(result.sizeBytes > 0); assert.ok(result.modifiedAt); assert.equal(result.resolvedPath, result.path);
 assert.doesNotMatch(JSON.stringify(result), /private-|tokens|OPENAI_API_KEY/);
});
test('does not inspect bridge files for a remote endpoint or non-file stores', async () => {
 for (const storage of ['keyring', 'ephemeral']) assert.equal((await readAuthFileInfo({ configResult: config('/not/read', storage) })).status, 'non_file');
 assert.equal((await readAuthFileInfo({ configResult: config('/not/read', 'auto') })).reason, 'automatic_storage');
 assert.equal((await readAuthFileInfo({ configResult: config('/not/read'), endpoint: 'ws://127.0.0.1:9999' })).reason, 'external_runtime');
 assert.equal(authFileLocation({ config: {}, layers: [] }).reason, 'storage_unknown');
 assert.equal(authFileLocation({ config: {cli_auth_credentials_store:'file'}, layers: [] }, {endpoint:'ws+unix:///server.sock:/',spawnedHome:'/wrong'}).reason, 'home_unknown');
});
test('only spawned local runtime may use its explicit home fallback', () => {
 assert.equal(authFileLocation({ config:{cli_auth_credentials_store:'file'}, layers:[] }, {spawnedHome:'/spawned'}).path, '/spawned/auth.json');
 assert.equal(authFileLocation({ config:{cli_auth_credentials_store:'file'}, layers:[...config('/a').layers,...config('/b').layers] }, {spawnedHome:'/wrong'}).reason, 'home_unknown');
});
test('missing file is explicit and is never an authenticated state', async t => {
 const result = await readAuthFileInfo({ configResult: config(await fixture(t)) }); assert.equal(result.status, 'missing'); assert.ok(result.path.endsWith('auth.json'));
});
test('malformed JSON retains type, code, stage and frames while redacting credential fragments', async t => {
 const home = await fixture(t); await fs.writeFile(path.join(home, 'auth.json'), '{"tokens":"VERY_SECRET_TOKEN", broken');
 await assert.rejects(readAuthFileInfo({ configResult: config(home) }), error => {
  assert.ok(error instanceof SyntaxError); assert.equal(error.code, 'INVALID_AUTH_JSON'); assert.equal(error.authFileStage, 'parse');
  assert.doesNotMatch(error.stack, /VERY_SECRET_TOKEN/); assert.match(error.stack, /at safeParse/);
  const failure = authFileFailure(error); assert.equal(failure.error.type, 'SyntaxError'); assert.equal(failure.error.stage, 'parse'); assert.doesNotMatch(JSON.stringify(failure), /VERY_SECRET_TOKEN/); return true;
 });
});
test('read permission error preserves original exception and diagnostic code', async t => {
 const error = Object.assign(new Error('Permission denied'), {code:'EACCES',cause:new Error('original cause')});
 await assert.rejects(readAuthFileInfo({configResult:config(await fixture(t)),io:{open:async()=>{throw error}}}), caught => {
  assert.equal(caught,error);assert.equal(caught.cause.message,'original cause');assert.equal(authFileFailure(caught).error.code,'EACCES');assert.equal(caught.authFileStage,'open');return true;
 });
});
test('large or non-regular files are rejected before parsing', async t => {
 const home = await fixture(t);const file=path.join(home,'auth.json');await fs.writeFile(file,'x'.repeat(MAX_AUTH_BYTES+1));
 await assert.rejects(readAuthFileInfo({configResult:config(home)}),{code:'AUTH_FILE_TOO_LARGE'});
 await fs.unlink(file);await fs.mkdir(file);await assert.rejects(readAuthFileInfo({configResult:config(home)}),{code:'NOT_REGULAR_FILE'});
});
test('symlink reports configured path and actual backing file', async t => {
 const home = await fixture(t);const target=path.join(home,'active-account.json');await fs.writeFile(target,'{"auth_mode":"apikey"}');await fs.symlink(target,path.join(home,'auth.json'));
 const result=await readAuthFileInfo({configResult:config(home)});assert.equal(result.resolvedPath,target);assert.equal(result.path,path.join(home,'auth.json'));
});
test('cleanup errors cannot hide a primary read failure', async t => {
 const primary=Object.assign(new Error('Read failed'),{code:'EIO'});const cleanup=new Error('Close failed');
 const io={open:async()=>({stat:async()=>({isFile:()=>true,size:12}),read:async()=>{throw primary},close:async()=>{throw cleanup}})};
 await assert.rejects(readAuthFileInfo({configResult:config(await fixture(t)),io}), error=>{assert.equal(error,primary);assert.deepEqual(error.suppressed,[cleanup]);assert.equal(error.authFileStage,'read');return true});
});
test('unsupported config RPC retains the numeric protocol error code as diagnostic text', () => {
 const error=Object.assign(new Error('Method not found'),{code:-32601});
 const info=authFileFailure(error);assert.equal(info.error.code,'-32601');assert.equal(info.error.stage,'config_read');assert.equal(info.error.message,'Method not found');
});
test('short filesystem reads are completed before parsing', async t => {
 const home=await fixture(t);await fs.writeFile(path.join(home,'auth.json'),'{"auth_mode":"chatgpt"}');
 const io={...fs,open:async(...args)=>{const h=await fs.open(...args);return{stat:()=>h.stat(),read:(buffer,offset,length,position)=>h.read(buffer,offset,Math.min(length,2),position),close:()=>h.close()}}};
 assert.equal((await readAuthFileInfo({configResult:config(home),io})).authMode,'chatgpt');
});
test('atomic auth rotation is reported as changed rather than attaching old metadata to new file', async t => {
 const home=await fixture(t);const file=path.join(home,'auth.json');await fs.writeFile(file,'{"auth_mode":"chatgpt"}');
 let rotated=false;
 const io={...fs,open:async(...args)=>{const h=await fs.open(...args);return{stat:()=>h.stat(),read:async(...readArgs)=>{const r=await h.read(...readArgs);if(!rotated){rotated=true;await fs.writeFile(file+'.new','{"auth_mode":"apikey"}');await fs.rename(file+'.new',file)}return r},close:()=>h.close()}}};
 await assert.rejects(readAuthFileInfo({configResult:config(home),io}),{code:'AUTH_FILE_CHANGED'});
});
test('runtime boundary returns metadata failure without hiding account or original error', async () => {
 const {readRuntimeAuthFileInfo}=require('../src/auth-file-info');
 const root=new Error('original cause');const error=Object.assign(new Error('Method not found',{cause:root}),{code:-32601});let logged;
 const result=await readRuntimeAuthFileInfo({sendRequest:async(method,params)=>{assert.equal(method,'config/read');assert.deepEqual(params,{includeLayers:true});throw error},logError:(label,caught)=>{logged=caught}});
 assert.equal(logged,error);assert.equal(logged.cause,root);assert.equal(result.status,'error');assert.equal(result.error.code,'-32601');assert.equal(result.error.stage,'config_read');
});
test('runtime boundary does not ask an external server for local paths', async () => {
 const {readRuntimeAuthFileInfo}=require('../src/auth-file-info');
 assert.equal((await readRuntimeAuthFileInfo({endpoint:'wss://example.test/codex',sendRequest:()=>{throw new Error('must not request')}})).reason,'external_runtime');
});
test('failed diagnostic logger cannot replace the original runtime failure', async () => {
 const {readRuntimeAuthFileInfo}=require('../src/auth-file-info');
 const primary=Object.assign(new Error('Configuration unavailable'),{code:-32000});const secondary=new Error('Diagnostic writer stopped');
 const result=await readRuntimeAuthFileInfo({sendRequest:async()=>{throw primary},logError:()=>{throw secondary}});
 assert.equal(result.error.code,'-32000');assert.equal(result.error.message,'Configuration unavailable');assert.deepEqual(primary.suppressed,[secondary]);
});
