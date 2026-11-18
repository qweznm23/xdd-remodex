const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const fs = require('node:fs/promises');
const path = require('node:path');
const run = promisify(execFile);

// Account maintenance runs outside the chat daemon and never blocks its router.
async function manageAuthProfiles(action, params, authFile, {
  manager = path.join(__dirname, 'auth_store.py'),
  execute = run, endpoint = "",
} = {}) {
  if (authFile?.status !== 'present' || authFile.storage !== 'file') {
    throw Object.assign(new Error('The connected Codex does not expose a managed local auth file'), { errorCode: 'AUTH_PROFILES_UNAVAILABLE' });
  }
  if (!['list', 'status', 'check', 'import', 'export', 'maintenance', 'query', 'refreshToken', 'switch'].includes(action)) throw new Error('Invalid auth profile action');
  // Validate the wire format here; the host manager authoritatively checks registration.
  const validProfile = typeof params.profile === 'string' && /^[1-9][0-9]*$/.test(params.profile) && params.profile.trim() === params.profile;
  if (!['list', 'status', 'maintenance'].includes(action) && (!(validProfile || (['refreshToken', 'check', 'export'].includes(action) && params.profile === "current")) || !(action === 'import' && params.revision === 'missing') && !/^\d+:\d+$/.test(params.revision || ''))) {
    throw Object.assign(new Error('Refresh the authorization list before selecting a profile'), { errorCode: 'INVALID_PROFILE_SELECTION' });
  }
  try { await fs.access(manager); }
  catch (cause) {
    if (cause.code === 'ENOENT' && action === 'list') return { status: 'unavailable', profiles: [] };
    throw cause;
  }
  const args = [path.join(__dirname, 'auth-profiles.py'), '--home', path.dirname(authFile.path), action];
  if (!['list', 'status', 'maintenance'].includes(action)) args.push('--profile', params.profile, '--revision', params.revision);
  if (action === 'import') {
    if (typeof params.sourcePath !== 'string' || !path.isAbsolute(params.sourcePath)) throw new Error('Import requires a completed workspace upload path');
    args.push('--source', params.sourcePath);
  }
  if (action === 'maintenance' && params.dryRun === true) args.push('--dry-run');
  const socket = /^ws\+unix:\/\/(\/[^:]+):\/$/.exec(endpoint)?.[1];
  if (socket && (['list', 'status', 'maintenance'].includes(action) || params.profile === 'current')) args.push('--socket', socket);
  let response;
  try {
    const { stdout } = await execute('/usr/bin/python3', args, { timeout: action === "maintenance" ? 930_000 : 240_000, maxBuffer: 1024 * 1024 });
    response = JSON.parse(stdout);
  } catch (cause) {
    const error = new Error(`Authorization manager failed at process/response: ${cause.message}`, { cause });
    error.errorCode = 'AUTH_MANAGER_PROCESS_FAILED';
    console.error('[auth-profiles] operation=%s stage=process', action, error);
    throw error;
  }
  if (!response.ok) {
    const diagnostic = response.error;
    const error = new Error(diagnostic.message);
    error.errorCode = diagnostic.code || 'AUTH_PROFILE_FAILED';
    error.profileDiagnostic = diagnostic;
    console.error('[auth-profiles] %s', JSON.stringify(diagnostic));
    throw error;
  }
  return response.result;
}

// Verify the actual connected daemon, not an isolated quota process or a past PID check.
// Credentials stay on the host and normal polling never forces token rotation.
async function verifySwitchedRuntimeImpl(result, authFile, sendRequest, readFile) {
  if (result.restartStatus !== 'restarted') return result;
  const expectedRaw = await readFile(authFile.path, 'utf8');
  let expected;
  try { expected = JSON.parse(expectedRaw); }
  catch (cause) {
    const frames = (cause.stack || '').split('\n').filter(line => /^\s+at /.test(line));
    const position = cause.message.match(/position (\d+)/)?.[1];
    cause.message = `Invalid auth JSON${position ? ` at position ${position}` : ''}; credentials redacted`;
    cause.stack = `${cause.name}: ${cause.message}\n${frames.join('\n')}`;
    throw Object.assign(new Error('Cannot verify selected auth file', { cause }), { errorCode: 'AUTH_VERIFY_FILE_INVALID' });
  }
  const auth = await sendRequest('getAuthStatus', { includeToken: true, refreshToken: false });
  const token = auth?.authToken;
  let runtimeAccount;
  if (typeof token === 'string') {
    try { runtimeAccount = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString())['https://api.openai.com/auth']?.chatgpt_account_id; }
    catch { /* Unknown token format is not proof of matching identity. */ }
  }
  const matches = typeof token === 'string' && token.length > 0 &&
    (token === expected.tokens?.access_token || (runtimeAccount && runtimeAccount === expected.tokens?.account_id));
  if (await readFile(authFile.path, 'utf8') !== expectedRaw) {
    throw Object.assign(new Error('Selected authorization changed during runtime verification'), { errorCode: 'AUTH_VERIFY_SUPERSEDED' });
  }
  return { ...result, restartStatus: matches ? 'ready' : 'verification_failed',
    restartError: matches ? null : 'stage=runtime-account-verification: connected Codex does not confirm the selected authorization',
  };
}

async function verifySwitchedRuntime(result, authFile, sendRequest, readFile = fs.readFile) {
  const secrets = new Set();
  const remember = token => { if (typeof token === 'string' && token) secrets.add(token); };
  try {
    return await verifySwitchedRuntimeImpl(result, authFile, async (...args) => {
      const reply = await sendRequest(...args);
      remember(reply?.authToken);
      return reply;
    }, async (...args) => {
      const raw = await readFile(...args);
      try { Object.values(JSON.parse(raw).tokens || {}).forEach(remember); } catch { /* Parser below preserves a sanitized cause. */ }
      return raw;
    });
  } catch (error) {
    const redact = value => {
      let text = String(value ?? '');
      for (const secret of secrets) text = text.split(secret).join('[REDACTED]');
      return text;
    };
    function diagnostic(cause, depth = 0) {
      if (depth > 8) return { truncated: true, message: 'Diagnostic cause depth limit' };
      const stack = redact(cause.stack);
      return { stage: 'runtime-account-verification', type: cause.name, code: cause.code || cause.errorCode,
        message: redact(cause.message), stack: stack.slice(0, 16000), truncated: stack.length > 16000,
        ...(cause.cause ? { cause: diagnostic(cause.cause, depth + 1) } : {}),
        ...(cause.suppressed ? { suppressed: cause.suppressed.map(value => diagnostic(value, depth + 1)) } : {}),
      };
    }
    error.profileDiagnostic = diagnostic(error);
    console.error('[auth-profiles] %s', JSON.stringify(error.profileDiagnostic));
    throw error;
  }
}

module.exports = { manageAuthProfiles, verifySwitchedRuntime };
