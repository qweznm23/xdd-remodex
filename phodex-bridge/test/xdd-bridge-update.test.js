const assert = require("node:assert/strict");
const test = require("node:test");
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const {execFileSync} = require("node:child_process");
const {installedVersion, selectRelease, stageRelease} = require("../src/xdd-bridge-update");

test("stage the matching XDD asset without changing the running installation", async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "xdd-update-test-"));
  t.after(() => fs.rm(dir, {recursive:true, force:true}));
  const current = path.join(dir, "xdd-remodex-server-0.1.7-ws-fix76");
  const next = "xdd-remodex-server-0.1.7-ws-fix77";
  const source = path.join(dir, "fixture", next, "phodex-bridge/bin");
  await fs.mkdir(source, {recursive:true});
  await fs.mkdir(current);
  await fs.writeFile(path.join(source, "remodex.js"), '"use strict";\n');
  const bytes = execFileSync("/usr/bin/tar", ["-czf", "-", "-C", path.join(dir, "fixture"), next]);
  const asset = {name:next + ".tar.gz", size:bytes.length, digest:"sha256:" + crypto.createHash("sha256").update(bytes).digest("hex"),
    browser_download_url:"https://github.com/qweznm23/xdd-remodex/releases/download/v0.1.7-ws-fix77/" + next + ".tar.gz"};
  const release = {tag_name:"v0.1.7-ws-fix77",assets:[asset]};
  const serve = (data, status = 200) => async url => url === asset.browser_download_url ? new Response(data, {status}) : Response.json(release);
  assert.equal(installedVersion(current), "0.1.7-ws-fix76");
  assert.equal(selectRelease(release, "0.1.7-ws-fix77"), null);
  assert.equal(selectRelease(release, "0.2.0-ws-fix1"), null);
  await assert.rejects(stageRelease(current, serve(bytes, 403)), error => error.httpStatus === 403);
  await assert.rejects(stageRelease(current, serve(Buffer.alloc(bytes.length))), /checksum mismatch/);
  const result = await stageRelease(current, serve(bytes));
  assert.equal(result.version, "0.1.7-ws-fix77");
  assert.equal(await fs.readFile(result.cli, "utf8"), '"use strict";\n');
  assert.deepEqual(await fs.readdir(current), []);
  assert.deepEqual(await stageRelease(current, serve(bytes)), result);
  assert.throws(() => selectRelease({...release, assets:[{...asset, browser_download_url:"https://example.org/x"}]}, "0.1.7-ws-fix1"), /verified/);
});

