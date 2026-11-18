const fs = require('node:fs/promises');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { manageAuthProfiles } = require('./auth-profiles');

// The Python owner holds the shared host lock and records the Beijing calendar day
// before requesting refresh. Ticks recover missed runs after restart without retry loops.
function createAuthMaintenance({ resolveAuthFile, endpoint, enabled = process.env.REMODEX_AUTH_SCHEDULE_ENABLED !== '0',
  manage = manageAuthProfiles, intervalMs = 60_000, log = console.error }) {
  let stopped = false;
  let running = false;
  const startedAt = new Date().toISOString();
  const instance = randomUUID();
  async function tick() {
    if (stopped || running || !enabled) return;
    running = true;
    try {
      const file = await resolveAuthFile();
      if (stopped || file.status !== 'present' || file.storage !== 'file') return;
      const result = await manage('maintenance', {}, file, { endpoint });
      const state = path.join(path.dirname(file.path), 'auth-profiles', 'maintenance');
      await fs.mkdir(state, { recursive: true, mode: 0o700 });
      const status = { owner: 'remodex', pid: process.pid, instance, startedAt,
        lastCheckAt: new Date().toISOString(), nextCheck: result.nextCheck,
        status: result.status, failedProfiles: (result.results || []).filter(row => row.status === 'failed').map(row => row.profile) };
      const temporary = path.join(state, '.scheduler-' + instance + '.tmp');
      await fs.writeFile(temporary, JSON.stringify(status) + '\n', { mode: 0o600 });
      await fs.rename(temporary, path.join(state, 'remodex-scheduler.json'));
    } catch (error) {
      // manageAuthProfiles preserves sanitized Python stage/code/cause diagnostics.
      log('[auth-maintenance]', { stage: 'scheduled-check', code: error.errorCode || error.code,
        message: error.message, stack: error.stack });
    } finally {
      running = false;
    }
  }
  const timer = enabled ? setInterval(tick, intervalMs) : null;
  timer?.unref();
  const startup = enabled ? setTimeout(tick, 15_000) : null;
  startup?.unref();
  return { tick, stop() { stopped = true; clearInterval(timer); clearTimeout(startup); } };
}
module.exports = { createAuthMaintenance };
