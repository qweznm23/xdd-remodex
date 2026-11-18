// One private, atomically replaced result per account/thread. The filename is an opaque hash.
const fs = require("node:fs/promises");
const path = require("node:path");
const { randomUUID } = require("node:crypto");

function createPredictionCache(directory) {
  const writers = new Map();
  async function read(key, sourceTurnId) {
    let raw;
    try { raw = await fs.readFile(path.join(directory, key + ".json"), "utf8"); }
    catch (error) { if (error.code === "ENOENT") return undefined; throw error; }
    const result = JSON.parse(raw);
    if (typeof result?.sourceTurnId !== "string" || (result.text !== null && typeof result.text !== "string")) {
      throw new Error("Invalid persisted composer prediction");
    }
    return result.sourceTurnId === sourceTurnId ? result : undefined;
  }

  function write(key, result, current) {
    const previous = writers.get(key);
    const replace = async () => {
      if (!current()) return;
      await fs.mkdir(directory, { mode: 0o700, recursive: true });
      const target = path.join(directory, key + ".json");
      const tmp = target + "." + randomUUID();
      try {
        await fs.writeFile(tmp, JSON.stringify(result), { mode: 0o600, flag: "wx" });
        if (current()) await fs.rename(tmp, target);
        else await fs.unlink(tmp);
      } catch (error) {
        try { await fs.unlink(tmp); }
        catch (cleanup) { if (cleanup.code !== "ENOENT") error.cleanupFailure = cleanup; }
        throw error;
      }
    };
    // Each caller owns its write's rejection. A later turn can still repair a failed write.
    const task = previous ? previous.then(replace, replace) : replace();
    writers.set(key, task);
    return task.finally(() => { if (writers.get(key) === task) writers.delete(key); });
  }
  return { read, write };
}

module.exports = { createPredictionCache };
