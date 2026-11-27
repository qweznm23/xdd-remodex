const os = require('node:os');
const fs = require('node:fs/promises');
const path = require('node:path');
const {setTimeout: delay} = require('node:timers/promises');

function linuxCounters(stat) {
  const counters = new Map();
  for (const match of stat.matchAll(/^cpu(\d+)\s+([^\n]+)$/gm)) {
    // Guest time is already counted in user/nice. Idle and I/O wait are not busy.
    const times = match[2].trim().split(/\s+/).slice(0, 8).map(Number);
    if (times.length < 4 || times.some(time => !Number.isSafeInteger(time) || time < 0)) {
      throw new Error('Invalid CPU counters for cpu' + match[1]);
    }
    counters.set(Number(match[1]), {total: times.reduce((a,b) => a+b, 0), idle: times[3] + (times[4] || 0)});
  }
  return counters;
}

async function readCounters() {
  if (process.platform === 'linux') return linuxCounters(await fs.readFile('/proc/stat', 'utf8'));
  return new Map(os.cpus().map(({times}, id) => [id, {
    total: Object.values(times).reduce((a,b) => a+b, 0), idle: times.idle,
  }]));
}

function utilization(before, after) {
  let total = 0;
  let busy = 0;
  const cores = Array.from(after, ([id, current]) => {
    const old = before.get(id);
    let percent = null;
    if (old) {
      const elapsed = current.total - old.total;
      const idle = current.idle - old.idle;
      if (elapsed > 0 && idle >= 0 && idle <= elapsed) {
        total += elapsed;
        busy += elapsed - idle;
        percent = Math.round(100 * (elapsed-idle) / elapsed);
      }
    }
    return {id, percent};
  });
  return {percent: total ? Math.round(100 * busy / total) : null, cores};
}

let pendingSample;
function sampleCpu() {
  if (!pendingSample) pendingSample = (async () => {
    const before = await readCounters();
    await delay(500);
    return utilization(before, await readCounters());
  })().finally(() => { pendingSample = undefined; });
  return pendingSample;
}

async function readSystemInfo(cwd, bridgeVersion, diagnostic) {
  const directory = cwd == null ? process.cwd() : cwd;
  if (typeof directory !== 'string' || !path.isAbsolute(directory)) throw new TypeError('Expected an absolute workspace directory');
  const disk = fs.statfs(directory).then(stat => ({
    directory, available: stat.bavail * stat.bsize, total: stat.blocks * stat.bsize,
    used: (stat.blocks - stat.bfree) * stat.bsize,
  }), error => ({directory, error: diagnostic(error)}));
  const [cpu, volume] = await Promise.all([sampleCpu(), disk]);
  return {cpu, disk: volume, sampledAt: Date.now(), hostname: os.hostname(), system: os.type(),
    release: os.release(), arch: os.machine(), bridgeVersion, memoryFree: os.freemem(), memoryTotal: os.totalmem()};
}

module.exports = {readSystemInfo, linuxCounters, utilization};
