const test = require('node:test');
const assert = require('node:assert/strict');
const { manageAuthProfiles, verifySwitchedRuntime } = require('../src/auth-profiles');
const file = { status: 'present', storage: 'file', path: '/home/test/.codex/auth.json' };
test('third profile reaches host registration validation for query and switch', async () => {
  for (const action of ['query', 'switch', 'refreshToken']) {
    const result = await manageAuthProfiles(action, { profile: '3', revision: '123:456' }, file, { manager: __filename,
      execute: async (command, args) => {
        assert.deepEqual(args.slice(-5), [action, '--profile', '3', '--revision', '123:456']);
        return { stdout: JSON.stringify({ ok: true, result: { profile: '3' } }) };
      } });
    assert.equal(result.profile, '3');
  }
});
test('untrusted selection and non-file runtime never launch manager', async () => {
  let calls = 0;
  const options = { manager: __filename, execute: async () => { calls++; } };
  await assert.rejects(manageAuthProfiles('switch', { profile: '../1', revision: '1:2' }, file, options), /Refresh/);
  await assert.rejects(manageAuthProfiles('switch', { profile: '1', revision: 'shell text' }, file, options), /Refresh/);
  await assert.rejects(manageAuthProfiles('list', {}, { status: 'unavailable' }, options), /managed local/);
  assert.equal(calls, 0);
});
test('explicit python file and runtime home, no shell interpolation', async () => {
  const result = await manageAuthProfiles('query', { profile: '1', revision: '123:456' }, file, { manager: __filename,
    execute: async (command, args, opts) => {
      assert.equal(command, '/usr/bin/python3');
      assert.ok(args[0].endsWith('/auth-profiles.py'));
      assert.deepEqual(args.slice(-5), ['query', '--profile', '1', '--revision', '123:456']);
      assert.equal(args[2], '/home/test/.codex');
      assert.equal(opts.timeout, 240000);
      return { stdout: JSON.stringify({ ok: true, result: { profile: '1' } }) };
    } });
  assert.deepEqual(result, { profile: '1' });
});
test('remote error keeps stage, code, traceback and suppressed evidence', async () => {
  const diagnostic = { message: 'quota denied', code: 'QUERY_FAILED', stage: 'query_rate_limits', stack: 'trace',
    rpc: { code: 401 }, suppressed: [{ message: 'cleanup failed' }] };
  await assert.rejects(manageAuthProfiles('query', { profile: '1', revision: '1:2' }, file, { manager: __filename,
    execute: async () => ({ stdout: JSON.stringify({ ok: false, error: diagnostic }) }) }), error => {
      assert.deepEqual(error.profileDiagnostic, diagnostic); return true;
    });
});

test('runtime verification uses the connected daemon without forcing token refresh or leaking credentials', async () => {
  const token = 'fake-private-token';
  const result = await verifySwitchedRuntime({ restartStatus: 'restarted', operationId: 'op' }, file,
    async (method, params) => {
      assert.equal(method, 'getAuthStatus');
      assert.deepEqual(params, { includeToken: true, refreshToken: false });
      return { authToken: token };
    }, async () => JSON.stringify({ tokens: { access_token: token, account_id: 'private-id' } }));
  assert.equal(result.restartStatus, 'ready');
  assert.equal(result.operationId, 'op');
  assert.ok(!JSON.stringify(result).includes(token));
  assert.ok(!JSON.stringify(result).includes('private-id'));
});

test('running PID alone or wrong runtime account never marks the switch ready', async () => {
  for (const auth of [{}, { authToken: 'wrong-token' }]) {
    const result = await verifySwitchedRuntime({ restartStatus: 'restarted' }, file, async () => auth,
      async () => JSON.stringify({ tokens: { access_token: 'expected', account_id: 'id' } }));
    assert.equal(result.restartStatus, 'verification_failed');
    assert.match(result.restartError, /runtime-account-verification/);
  }
  const pending = { restartStatus: 'restart_scheduled' };
  assert.equal(await verifySwitchedRuntime(pending, file, () => assert.fail('must not probe old daemon')), pending);
});

test('legacy restart failure stays in its original record when the selected runtime has recovered', async () => {
  const failure = { stage: 'daemon_restart', exit_code: 1, stderr: 'fixture daemon rejected restart' };
  const legacy = { operationId: 'old', legacy: true, selectedOnDisk: true, status: 'failed', failure };
  const result = { restartStatus: 'failed', operationId: 'old', restartHistory: [legacy] };
  const expected = async () => JSON.stringify({ tokens: { access_token: 'fixture-token', account_id: 'id' } });
  const verified = await verifySwitchedRuntime(result, file, async () => ({ authToken: 'fixture-token' }), expected);
  assert.equal(verified.restartStatus, 'ready');
  assert.equal(verified.restartHistory[0].status, 'recovered');
  assert.deepEqual(verified.restartHistory[0].failure, failure);
  assert.equal(await verifySwitchedRuntime(result, file, async () => ({}), expected), result);
});

test('rotated runtime token for same account works and concurrent file switches are rejected', async () => {
  const token = 'header.' + Buffer.from(JSON.stringify({ 'https://api.openai.com/auth': { chatgpt_account_id: 'same-id' } })).toString('base64url') + '.signature';
  const raw = JSON.stringify({ tokens: { access_token: 'old', account_id: 'same-id' } });
  const result = await verifySwitchedRuntime({ restartStatus: 'restarted' }, file, async () => ({ authToken: token }), async () => raw);
  assert.equal(result.restartStatus, 'ready');
  let reads = 0;
  await assert.rejects(verifySwitchedRuntime({ restartStatus: 'restarted' }, file, async () => ({ authToken: token }),
    async () => ++reads === 1 ? raw : '{}'), error => error.errorCode === 'AUTH_VERIFY_SUPERSEDED');
});

test('verification preserves RPC cause and invalid JSON diagnostics redact secret fragments', async () => {
  const failure = Object.assign(new Error('RPC denied'), { code: 401, cause: new Error('oauth') });
  await assert.rejects(verifySwitchedRuntime({ restartStatus: 'restarted' }, file, async () => { throw failure; }, async () => '{}'),
    error => error === failure && error.code === 401 && error.cause.message === 'oauth' &&
      error.profileDiagnostic.stage === 'runtime-account-verification' && error.profileDiagnostic.code === 401 &&
      error.profileDiagnostic.cause.message === 'oauth' && error.profileDiagnostic.stack.includes('RPC denied'));
  await assert.rejects(verifySwitchedRuntime({ restartStatus: 'restarted' }, file, async () => ({}), async () => 'secret-token invalid json'),
    error => error.cause instanceof SyntaxError && !error.cause.stack.includes('secret-token'));
});

test('current refresh uses only the configured daemon socket', async () => {
  const options = { manager: __filename, endpoint: 'ws+unix:///host/codex.sock:/', execute: async (_, args) => {
    assert.deepEqual(args.slice(-7), ['refreshToken', '--profile', 'current', '--revision', '1:2', '--socket', '/host/codex.sock']);
    return { stdout: '{"ok":true,"result":{}}' };
  } };
  const params = { profile: 'current', revision: '1:2', socket: '/ignored.sock' };
  await manageAuthProfiles('refreshToken', params, file, options);
  await assert.rejects(manageAuthProfiles('switch', params, file, options), /Refresh/);
});
