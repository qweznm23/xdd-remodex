const fs = require("node:fs/promises");
const path = require("node:path");
const crypto = require("node:crypto");
const { promisify } = require("node:util");
const execFile = promisify(require("node:child_process").execFile);

const prefix = "xdd-remodex-server-";
const repository = "https://github.com/qweznm23/xdd-remodex";
const api = "https://api.github.com/repos/qweznm23/xdd-remodex/releases/latest";
const maxBytes = 64 * 1024 * 1024;
function version(value) {
  return /^(\d+)\.(\d+)\.(\d+)-ws-fix(\d+)$/.exec(value)?.slice(1).map(Number);
}
function installedVersion(dir) {
  const folder = path.basename(dir);
  const name = folder.startsWith(prefix) ? folder.slice(prefix.length) : "";
  return version(name) ? name : null;
}
function selectRelease(release, installed) {
  const next = release.tag_name?.replace(/^v/, "");
  const numbers = version(next);
  const previous = version(installed);
  if (!previous || !numbers || release.draft || release.prerelease) throw new Error("Invalid XDD stable release metadata");
  const index = numbers.findIndex((part, i) => part !== previous[i]);
  if (index < 0 || numbers[index] < previous[index]) return null;
  const folder = prefix + next;
  const asset = release.assets?.find(item => item.name === folder + ".tar.gz");
  const url = repository + "/releases/download/" + release.tag_name + "/" + folder + ".tar.gz";
  if (!asset || asset.browser_download_url !== url || !/^sha256:[0-9a-f]{64}$/.test(asset.digest) ||
      !Number.isSafeInteger(asset.size) || asset.size < 1 || asset.size > maxBytes) throw new Error("Missing verified XDD server asset");
  return { folder, url, size: asset.size, digest: asset.digest.slice(7), version: next };
}
async function http(url, fetchImpl) {
  const response = await fetchImpl(url, {signal: AbortSignal.timeout(60000), headers: {"User-Agent": "xdd-remodex-updater"}});
  if (!response.ok) {
    await response.body?.cancel();
    throw Object.assign(new Error("XDD update request: HTTP " + response.status), {httpStatus: response.status});
  }
  return response;
}
async function stageRelease(dir, fetchImpl = fetch) {
  let target;
  try {
    const release = await (await http(api, fetchImpl)).json();
    target = selectRelease(release, installedVersion(dir));
  } catch (error) { error.stage = "release_metadata"; throw error; }
  if (!target) return null;
  const parent = path.dirname(dir);
  const stage = await fs.mkdtemp(path.join(parent, ".xdd-update-"));
  let primary;
  let stageName = "download_asset";
  try {
    const response = await http(target.url, fetchImpl);
    const chunks = [];
    let size = 0;
    for await (const chunk of response.body) {
      size += chunk.length;
      if (size > target.size) throw new Error("XDD asset exceeded published size");
      chunks.push(chunk);
    }
    stageName = "verify_asset";
    const data = Buffer.concat(chunks);
    if (size !== target.size || crypto.createHash("sha256").update(data).digest("hex") !== target.digest) throw new Error("XDD asset checksum mismatch");
    stageName = "extract_asset";
    const archive = path.join(stage, "release.tar.gz");
    await fs.writeFile(archive, data, {mode: 0o600});
    const listing = await execFile("/usr/bin/tar", ["-tzf", archive], {maxBuffer: 8 * 1024 * 1024});
    if (listing.stdout.trim().split("\n").some(name => !name.startsWith(target.folder + "/") || name.split("/").includes(".."))) {
      throw new Error("Unexpected XDD archive layout");
    }
    // Only the bridge is upgraded; the running relay and private host state have separate lifetimes.
    await execFile("/usr/bin/tar", ["-xzf", archive, "-C", stage, target.folder]);
    const relativeCli = "phodex-bridge/bin/remodex.js";
    const staged = path.join(stage, target.folder);
    stageName = "verify_entrypoint";
    await execFile(process.execPath, ["--check", path.join(staged, relativeCli)]);
    await fs.writeFile(path.join(staged, ".xdd-asset-sha256"), target.digest, {flag:"wx", mode:0o600});
    stageName = "stage_release";
    const dest = path.join(parent, target.folder);
    try { await fs.rename(staged, dest); }
    catch (error) {
      if (!["EEXIST","ENOTEMPTY"].includes(error.code)) throw error;
      let existing;
      try { existing = await fs.readFile(path.join(dest, ".xdd-asset-sha256"), "utf8"); }
      catch (verificationError) { error.verificationError = verificationError; throw error; }
      if (existing !== target.digest) throw error;
    }
    return {cli: path.join(dest, relativeCli), version: target.version};
  } catch (error) { error.stage = stageName; primary = error; throw error; }
  finally {
    try { await fs.rm(stage, {recursive:true, force:true}); }
    catch (cleanup) { if (primary) primary.cleanupError = cleanup; else throw cleanup; }
  }
}
module.exports = {installedVersion, selectRelease, stageRelease};

