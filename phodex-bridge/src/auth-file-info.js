const fs = require('node:fs/promises');
const { constants } = require('node:fs');
const path = require('node:path');
const MAX_AUTH_BYTES = 64 * 1024;

function localRuntime(endpoint) {
  return !endpoint || endpoint.startsWith('ws+unix:');
}

function authFileLocation(configResult, { endpoint = '', spawnedHome = null } = {}) {
  if (!localRuntime(endpoint)) return { status: 'unavailable', reason: 'external_runtime' };
  const config = configResult?.config;
  if (!config || typeof config !== 'object') throw Object.assign(new Error('config/read did not return configuration'), { code: 'INVALID_CONFIG' });
  const storage = config.cli_auth_credentials_store;
  if (!['file', 'keyring', 'auto', 'ephemeral'].includes(storage)) return { status: 'unavailable', reason: 'storage_unknown' };
  if (storage === 'auto') return { status: 'unavailable', storage, reason: 'automatic_storage' };
  if (storage !== 'file') return { status: 'non_file', storage };
  const users = (configResult.layers || []).filter(layer => layer?.name?.type === 'user' && !layer.disabledReason);
  const homes = [...new Set(users.map(layer => layer.name.file).filter(file => typeof file === 'string' && path.isAbsolute(file) && path.basename(file) === 'config.toml').map(file => path.dirname(file)))];
  const home = homes.length === 1 ? homes[0] : (homes.length === 0 && !endpoint ? spawnedHome : null);
  if (!home || !path.isAbsolute(home)) return { status: 'unavailable', storage, reason: 'home_unknown' };
  return { status: 'located', storage, source: homes.length ? 'runtime_config' : 'runtime_environment', path: path.join(home, 'auth.json') };
}

function safeParse(text) {
  try {
    const data = JSON.parse(text);
    if (!data || typeof data !== 'object' || Array.isArray(data)) throw Object.assign(new Error('auth.json must contain an object'), { code: 'INVALID_AUTH_FILE' });
    return data;
  } catch (error) {
    if (error instanceof SyntaxError) {
      // V8 includes JSON fragments (possibly tokens) in SyntaxError messages.
      // Retain the type, position and stack frames, explicitly redact the contents.
      const position = error.message.match(/position (\d+)/)?.[1];
      const originalPrefix = `${error.name}: ${error.message}`;
      const originalStack = error.stack || '';
      const frames = (originalStack.startsWith(originalPrefix) ? originalStack.slice(originalPrefix.length) : '')
        .split('\n').filter(line => /^\s+at /.test(line));
      error.message = `auth.json is invalid JSON${position ? ` at position ${position}` : ''}; credential contents redacted`;
      error.stack = `${error.name}: ${error.message}\n${frames.join('\n')}`;
      error.code = 'INVALID_AUTH_JSON';
    }
    throw error;
  }
}

function timestamp(value) {
  if (value == null) return null;
  if (typeof value !== 'string' || value.length > 100 || !/^\d{4}-\d\d-\d\dT/.test(value) || !Number.isFinite(Date.parse(value))) {
    throw Object.assign(new Error('auth.json last_refresh is not a valid timestamp'), { code: 'INVALID_AUTH_TIMESTAMP' });
  }
  return new Date(value).toISOString();
}

async function readAuthFileInfo({ configResult, endpoint = '', spawnedHome = null, io = fs } = {}) {
  let stage = 'resolve';
  let location = {};
  let handle;
  let primary;
  try {
    location = authFileLocation(configResult, { endpoint, spawnedHome });
    if (location.status !== 'located') return location;
    stage = 'open';
    handle = await io.open(location.path, constants.O_RDONLY | constants.O_NONBLOCK);
    stage = 'stat';
    const stat = await handle.stat();
    if (!stat.isFile()) throw Object.assign(new Error('Configured auth.json is not a regular file'), { code: 'NOT_REGULAR_FILE' });
    if (stat.size > MAX_AUTH_BYTES) throw Object.assign(new Error('Authorization metadata read exceeds size limit'), { code: 'AUTH_FILE_TOO_LARGE' });
    stage = 'read';
    const buffer = Buffer.alloc(MAX_AUTH_BYTES + 1);
    let bytesRead = 0;
    while (bytesRead < buffer.length) {
      const part = await handle.read(buffer, bytesRead, buffer.length - bytesRead, bytesRead);
      if (part.bytesRead === 0) break;
      bytesRead += part.bytesRead;
    }
    if (bytesRead > MAX_AUTH_BYTES) throw Object.assign(new Error('Authorization metadata read exceeds size limit'), { code: 'AUTH_FILE_TOO_LARGE' });
    stage = 'parse';
    const data = safeParse(buffer.subarray(0, bytesRead).toString('utf8'));
    stage = 'metadata';
    const lastRefresh = timestamp(data.last_refresh);
    const authMode = ['chatgpt', 'apikey', 'apiKey', 'chatgptAuthTokens'].includes(data.auth_mode) ? data.auth_mode : null;
    const after = await handle.stat();
    if (after.mtimeMs !== stat.mtimeMs || after.size !== stat.size) throw Object.assign(new Error('Authorization file changed during read; refresh to read again'), { code: 'AUTH_FILE_CHANGED' });
    const resolvedPath = await io.realpath(location.path);
    const target = await io.stat(resolvedPath);
    if (target.ino !== stat.ino || target.dev !== stat.dev) throw Object.assign(new Error('Authorization file was replaced during read; refresh to read again'), { code: 'AUTH_FILE_CHANGED' });
    // Fixed whitelist: never send tokens, keys, claims, fingerprints or JSON contents.
    return { ...location, status: 'present', fileName: 'auth.json', resolvedPath, sizeBytes: stat.size,
      modifiedAt: stat.mtime.toISOString(), authMode, lastRefresh };
  } catch (error) {
    primary = error;
    error.authFileStage = stage;
    error.authFilePath = location.path;
    error.authFileStorage = location.storage;
    if (stage === 'open' && error.code === 'ENOENT') return { ...location, status: 'missing' };
    throw error;
  } finally {
    if (handle) {
      try { await handle.close(); }
      catch (cleanup) {
        if (primary) (primary.suppressed ||= []).push(cleanup);
        else { cleanup.authFileStage = 'close'; throw cleanup; }
      }
    }
  }
}

function authFileFailure(error) {
  return { status: 'error', path: error.authFilePath || null, storage: error.authFileStorage || null,
    error: { stage: error.authFileStage || 'config_read', code: String(error.code ?? error.errorCode ?? 'AUTH_FILE_READ_FAILED'), type: error.name || 'Error', message: error.message } };
}

async function readRuntimeAuthFileInfo({ sendRequest, endpoint = '', spawnedHome = null, logError = console.error } = {}) {
  if (!localRuntime(endpoint)) return { status: 'unavailable', reason: 'external_runtime' };
  try {
    const configResult = await sendRequest('config/read', { includeLayers: true });
    return await readAuthFileInfo({ configResult, endpoint, spawnedHome });
  } catch (error) {
    const failure = authFileFailure(error);
    try { logError(`[remodex] operation=auth-file-info stage=${failure.error.stage} code=${failure.error.code}`, error); }
    catch (loggingError) { (error.suppressed ||= []).push(loggingError); }
    return failure;
  }
}

module.exports = { readRuntimeAuthFileInfo, readAuthFileInfo, authFileLocation, authFileFailure, MAX_AUTH_BYTES };
