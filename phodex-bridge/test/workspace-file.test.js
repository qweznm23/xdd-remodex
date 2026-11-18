// FILE: workspace-file.test.js
// Purpose: Verifies bridge-side read-only text file previews stay scoped and size-safe.
// Layer: Unit test
// Exports: node:test suite
// Depends on: node:test, node:assert/strict, fs, os, path, ../src/workspace-handler

const test = require("node:test");
const assert = require("node:assert/strict");
const { execFileSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { handleWorkspaceMethod } = require("../src/workspace-handler");

function makeGitWorkspace() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "remodex-file-"));
  execFileSync("git", ["init"], { cwd: tempDir, stdio: "ignore" });
  return tempDir;
}

test("workspace/readFile returns UTF-8 text for a file inside cwd", async () => {
  const tempDir = makeGitWorkspace();
  const filePath = path.join(tempDir, "src", "App.swift");
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, "let value = 1\nprint(value)\n", "utf8");

  const result = await handleWorkspaceMethod("workspace/readFile", {
    cwd: tempDir,
    path: "src/App.swift",
  });

  assert.equal(result.path, fs.realpathSync(filePath));
  assert.equal(result.fileName, "App.swift");
  assert.equal(result.content, "let value = 1\nprint(value)\n");
  assert.equal(result.lineCount, 2);
  assert.equal(result.encoding, "utf-8");
  assert.equal(typeof result.mtimeMs, "number");
});

test("workspace/readFile can return metadata without file content", async () => {
  const tempDir = makeGitWorkspace();
  const filePath = path.join(tempDir, "README.md");
  fs.writeFileSync(filePath, "# Demo\n", "utf8");

  const result = await handleWorkspaceMethod("workspace/readFile", {
    cwd: tempDir,
    path: filePath,
    includeContent: false,
  });

  assert.equal(result.path, fs.realpathSync(filePath));
  assert.equal(result.content, undefined);
});

test("workspace/readFile resolves a unique bare filename inside the workspace", async () => {
  const tempDir = makeGitWorkspace();
  const filePath = path.join(tempDir, "phodex-bridge", "test", "bridge-desktop-ipc-integration.test.js");
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, "test('demo', () => {})\n", "utf8");

  const result = await handleWorkspaceMethod("workspace/readFile", {
    cwd: tempDir,
    path: "bridge-desktop-ipc-integration.test.js",
  });

  assert.equal(result.path, fs.realpathSync(filePath));
  assert.equal(result.content, "test('demo', () => {})\n");
});

test("workspace/readFile rejects ambiguous bare filename matches", async () => {
  const tempDir = makeGitWorkspace();
  const firstPath = path.join(tempDir, "Sources", "App.swift");
  const secondPath = path.join(tempDir, "Tests", "App.swift");
  fs.mkdirSync(path.dirname(firstPath), { recursive: true });
  fs.mkdirSync(path.dirname(secondPath), { recursive: true });
  fs.writeFileSync(firstPath, "let first = true\n", "utf8");
  fs.writeFileSync(secondPath, "let second = true\n", "utf8");

  await assert.rejects(
    () => handleWorkspaceMethod("workspace/readFile", {
      cwd: tempDir,
      path: "App.swift",
    }),
    /Multiple files named "App.swift"/
  );
});

test("workspace/readFile skips content when cached metadata still matches", async () => {
  const tempDir = makeGitWorkspace();
  const filePath = path.join(tempDir, "README.md");
  fs.writeFileSync(filePath, "# Demo\n", "utf8");

  const first = await handleWorkspaceMethod("workspace/readFile", {
    cwd: tempDir,
    path: filePath,
  });
  const second = await handleWorkspaceMethod("workspace/readFile", {
    cwd: tempDir,
    path: filePath,
    ifByteLength: first.byteLength,
    ifMtimeMs: first.mtimeMs,
  });

  assert.equal(second.notModified, true);
  assert.equal(second.content, undefined);
});

test("workspace/readFile rejects files outside the selected workspace", async () => {
  const tempDir = makeGitWorkspace();
  const outsideDir = fs.mkdtempSync(path.join(os.tmpdir(), "remodex-file-outside-"));
  const outsidePath = path.join(outsideDir, "secret.txt");
  fs.writeFileSync(outsidePath, "nope", "utf8");

  await assert.rejects(
    () => handleWorkspaceMethod("workspace/readFile", {
      cwd: tempDir,
      path: outsidePath,
    }),
    /Only files in the current workspace/
  );
});

test("workspace/readFile rejects binary files", async () => {
  const tempDir = makeGitWorkspace();
  const filePath = path.join(tempDir, "data.bin");
  fs.writeFileSync(filePath, Buffer.from([0x00, 0x01, 0x02, 0x03]));

  await assert.rejects(
    () => handleWorkspaceMethod("workspace/readFile", {
      cwd: tempDir,
      path: filePath,
    }),
    /looks binary/
  );
});

test("workspace downloads APKs larger than the preview limit in bounded verified chunks", async (t) => {
  const { createHash, randomBytes } = require("crypto");
  const dir = makeGitWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const bytes = randomBytes(3 * 1024 * 1024 + 7);
  fs.writeFileSync(path.join(dir, "app.apk"), bytes);
  const info = await handleWorkspaceMethod("workspace/fileInfo", { cwd: dir, path: "app.apk" });
  assert.equal(info.previewKind, "none");
  assert.equal(info.mimeType, "application/vnd.android.package-archive");
  const received = [];
  let offset = 0;
  while (offset < info.byteLength) {
    const chunk = await handleWorkspaceMethod("workspace/readFileChunk", { cwd: dir, path: info.path, revision: info.revision, offset });
    const data = Buffer.from(chunk.dataBase64, "base64");
    assert.ok(data.length <= 256 * 1024);
    assert.equal(chunk.sha256, createHash("sha256").update(data).digest("hex"));
    assert.equal(chunk.offset, offset);
    assert.equal(chunk.nextOffset, offset + data.length);
    offset = chunk.nextOffset;
    assert.equal(chunk.eof, offset === bytes.length);
    received.push(data);
  }
  assert.deepEqual(Buffer.concat(received), bytes);
});

test("file metadata distinguishes text, images, archives, and oversized text", async (t) => {
  const dir = makeGitWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  for (const [name, content, kind] of [
    ["readme.md", "你好\n", "text"], ["photo.png", Buffer.from([137, 80, 78, 71, 0]), "image"],
    ["archive.zip", "PKtest", "none"], ["large.log", "a".repeat(2 * 1024 * 1024 + 1), "none"],
    ["empty.txt", "", "text"], ["unknown.bin", Buffer.from([0, 255]), "none"],
  ]) {
    fs.writeFileSync(path.join(dir, name), content);
    const info = await handleWorkspaceMethod("workspace/fileInfo", { cwd: dir, path: name });
    assert.equal(info.previewKind, kind, name);
  }
});

test("download rejects a changed file and invalid offsets instead of mixing versions", async (t) => {
  const dir = makeGitWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.writeFileSync(path.join(dir, "app.apk"), "payload");
  const info = await handleWorkspaceMethod("workspace/fileInfo", { cwd: dir, path: "app.apk" });
  for (const offset of [-1, 1.5, 8, "0"]) {
    await assert.rejects(handleWorkspaceMethod("workspace/readFileChunk", { cwd: dir, path: info.path, revision: info.revision, offset }), e => e.errorCode === "invalid_offset");
  }
  fs.appendFileSync(info.path, "changed");
  await assert.rejects(handleWorkspaceMethod("workspace/readFileChunk", { cwd: dir, path: info.path, revision: info.revision, offset: 0 }), e => e.errorCode === "file_changed");
});

test("downloads reject symlink escapes and retain diagnostic stage and error code", async (t) => {
  const { handleWorkspaceRequest } = require("../src/workspace-handler");
  const dir = makeGitWorkspace();
  const outside = fs.mkdtempSync(path.join(os.tmpdir(), "remodex-outside-"));
  t.after(() => { fs.rmSync(dir, { recursive: true, force: true }); fs.rmSync(outside, { recursive: true, force: true }); });
  fs.writeFileSync(path.join(outside, "secret.apk"), "secret");
  fs.symlinkSync(path.join(outside, "secret.apk"), path.join(dir, "escape.apk"));
  await assert.rejects(handleWorkspaceMethod("workspace/fileInfo", { cwd: dir, path: "escape.apk" }), e => e.errorCode === "file_path_not_allowed");
  const response = await new Promise(resolve => handleWorkspaceRequest(JSON.stringify({ id: 1, method: "workspace/fileInfo", params: {} }), raw => resolve(JSON.parse(raw))));
  assert.equal(response.error.data.errorCode, "missing_file_path");
  assert.equal(response.error.data.operation, "workspace/fileInfo");
  assert.equal(response.error.data.stage, "workspace_file_transfer");
  assert.match(response.error.data.diagnostic.stack, /workspaceDownloadFile/);
});

test('video files expose player MIME types and never enter text or image previews', async () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'remodex-video-types-'));
  try {
    for (const [extension, mime] of [['mp4','video/mp4'],['webm','video/webm'],['mov','video/quicktime'],['mkv','video/x-matroska'],['m4v','video/mp4']]) {
      const file = path.join(tempDir, 'clip.' + extension);
      fs.writeFileSync(file, 'synthetic video fixture');
      const metadata = await handleWorkspaceMethod('workspace/fileInfo', {cwd:tempDir,path:file});
      assert.equal(metadata.mimeType,mime);
      assert.equal(metadata.previewKind,'none');
    }
  } finally { fs.rmSync(tempDir,{recursive:true,force:true}); }
});
