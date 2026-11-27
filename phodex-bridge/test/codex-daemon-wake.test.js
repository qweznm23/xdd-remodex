// The fake daemon speaks WebSocket on an isolated Codex control socket.
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const http = require("node:http");
const { once, EventEmitter } = require("node:events");
const { WebSocketServer } = require("ws");
const { createCodexTransport } = require("../src/codex-transport");

async function fixture(t) {
  const home = await fs.mkdtemp(path.join(os.tmpdir(), "rd-wake-"));
  const socket = path.join(home, "app-server-control", "app-server-control.sock");
  const options = { endpoint: "ws+unix://" + socket + ":/", env: { CODEX_HOME: home }, platform: "linux" };
  await fs.mkdir(path.dirname(socket));
  t.after(() => fs.rm(home, { recursive: true, force: true }));
  return { socket, options };
}

test("canonical socket wakes once on cold open and once after a running daemon disappears", { timeout: 10000 }, async t => {
  const { socket, options } = await fixture(t);
  let starts = 0, server, ws;
  async function listen(command, args) {
    assert.equal(command, "codex");
    assert.deepEqual(args, ["app-server", "daemon", "start"]);
    starts++;
    server = http.createServer();
    ws = new WebSocketServer({ server });
    server.listen(socket);
    await once(server, "listening");
  }
  async function stopListening() {
    for (const client of ws.clients) client.terminate();
    ws.close();
    await new Promise(resolve => server.close(resolve));
  }
  const transport = createCodexTransport({ ...options, runCommand: listen });
  t.after(async () => { transport.shutdown(); if (server?.listening) await stopListening(); });
  const events = new EventEmitter();
  transport.onStarted(info => events.emit("up", info));
  transport.onError(error => events.emit("error", error));
  const [initial] = await once(events, "up");
  assert.equal(initial.reconnected, false);
  assert.equal(starts, 1);
  let interrupted = 0;
  transport.onRestarting(() => interrupted++);
  const recovered = once(events, "up");
  await stopListening();
  assert.equal((await recovered)[0].reconnected, true);
  assert.equal(interrupted, 1);
  assert.equal(starts, 2);
});

test("only an absent listener on the managed socket can trigger a start, preserving both failure causes", async t => {
  const { options } = await fixture(t);
  for (const [endpoint, code, expected] of [
    [options.endpoint, "ECONNREFUSED", true], [options.endpoint, "EACCES", false],
    ["ws+unix:///tmp/custom.sock:/", "ENOENT", false], ["ws://127.0.0.1:1234", "ECONNREFUSED", false],
  ]) {
    let socket, starts = 0;
    class Socket extends EventEmitter {
      constructor() { super(); socket = this; }
      close() {}
    }
    const startError = Object.assign(new Error("exited with diagnostics"), { code: 7 });
    const transport = createCodexTransport({ ...options, endpoint, WebSocketImpl: Socket,
      runCommand: async () => { starts++; throw startError; } });
    const failure = new Promise(resolve => transport.onError(resolve));
    const connectError = Object.assign(new Error(code), { code });
    socket.emit("error", connectError);
    socket.emit("close", 1006);
    const error = await failure;
    assert.equal(starts, expected ? 1 : 0);
    if (expected) {
      assert.equal(error.cause, startError);
      assert.equal(error.stage, "codex_daemon_start");
      assert.equal(error.code, 7);
      assert.equal(error.socketError, connectError);
    } else {
      assert.equal(error, connectError);
    }
    transport.shutdown();
  }
});

test("reconnects without a launch when the listener stays up or an account switch is already restarting it", { timeout: 5000 }, async t => {
  const { options } = await fixture(t);
  let socket, observed;
  class Socket extends EventEmitter {
    constructor() { super(); socket = this; observed?.(this); }
    close() {}
  }
  const transport = createCodexTransport({ ...options, WebSocketImpl: Socket, runCommand: () => assert.fail("unexpected launch") });
  t.after(() => transport.shutdown());
  socket.emit("open");
  let next = new Promise(resolve => { observed = resolve; });
  socket.emit("close", 1006);
  (await next).emit("open");
  transport.expectRestart();
  next = new Promise(resolve => { observed = resolve; });
  socket.emit("close", 1006);
  const gap = await next;
  gap.emit("error", Object.assign(new Error("planned downtime"), {code:"ENOENT"}));
  next = new Promise(resolve => { observed = resolve; });
  gap.emit("close", 1006);
  const resumed = new Promise(resolve => transport.onStarted(resolve));
  (await next).emit("open");
  assert.equal((await resumed).reconnected, true);
});
