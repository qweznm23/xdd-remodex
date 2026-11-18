// FILE: bridge-desktop-ipc-integration.test.js
// Purpose: Verifies the bridge wires phone-origin replies to Codex Desktop IPC actions.
// Layer: Integration test
// Exports: node:test suite
// Depends on: node:test, ws, net, ../src/bridge with mocked runtime transports

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const Module = require("node:module");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { setTimeout: wait } = require("node:timers/promises");
const WebSocket = require("ws");

test("bridge forwards desktop IPC actions to the phone and routes replies back to Codex Desktop", async (t) => {
  const { tempDir, socketPath: ipcSocketPath } = createIpcTestSocket("remodex-bridge-ipc-");
  const relayServer = new WebSocket.Server({ port: 0 });
  const relayMessages = [];
  const ipcFrames = [];
  let relaySocket = null;
  let ipcServerSocket = null;
  let bridge = null;
  let fakeCodex = null;

  await new Promise((resolve) => relayServer.once("listening", resolve));
  relayServer.on("connection", (socket) => {
    relaySocket = socket;
    socket.on("message", (data) => {
      const parsed = safeParseJSON(data.toString("utf8"));
      if (parsed) {
        relayMessages.push(parsed);
      }
    });
  });

  const ipcServer = net.createServer((socket) => {
    ipcServerSocket = socket;
    attachFrameReader(socket, (frame) => {
      ipcFrames.push(frame);
      if (frame.method === "initialize") {
        writeFrame(socket, {
          type: "response",
          requestId: frame.requestId,
          resultType: "success",
          method: "initialize",
          handledByClientId: "desktop",
          result: { clientId: "desktop-test" },
        });
      }
      if (frame.method === "thread-follower-submit-user-input") {
        writeFrame(socket, {
          type: "response",
          requestId: frame.requestId,
          resultType: "success",
          method: frame.method,
          handledByClientId: "desktop",
          result: { ok: true },
        });
      }
    });
  });
  await new Promise((resolve) => ipcServer.listen(ipcSocketPath, resolve));

  const { startBridge } = loadBridgeWithTestDoubles({
    createCodexTransportImpl() {
      fakeCodex = createFakeCodexTransport();
      return fakeCodex;
    },
  });

  t.after(() => {
    bridge?.stop();
    relaySocket?.close();
    relayServer.close();
    ipcServer.close();
    ipcServerSocket?.destroy();
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  bridge = startBridge({
    printPairingQr: false,
    config: {
      relayUrl: `ws://127.0.0.1:${relayServer.address().port}`,
      pushServiceUrl: "",
      pushPreviewMaxChars: 160,
      refreshEnabled: false,
      refreshDebounceMs: 1,
      keepMacAwakeEnabled: false,
      codexEndpoint: "",
      refreshCommand: "",
      codexBundleId: "",
      codexAppPath: "",
      desktopIpcSocketPath: ipcSocketPath,
    },
  });

  await waitFor(() => relaySocket && relaySocket.readyState === WebSocket.OPEN);
  relaySocket.send(JSON.stringify({
    id: "resume-from-phone",
    method: "thread/resume",
    params: { threadId: "thread-ipc" },
  }));

  await waitFor(() => ipcServerSocket, 2_000);
  await wait(25);
  assert.equal(
    fakeCodex.sent.some((message) => message.method === "thread/read"),
    false
  );

  writeFrame(ipcServerSocket, {
    type: "broadcast",
    method: "thread-stream-state-changed",
    sourceClientId: "desktop",
    version: 1,
    params: {
      conversationId: "thread-ipc",
      change: {
        type: "snapshot",
        conversationState: {
          requests: [{
            id: "req-ipc",
            method: "item/tool/requestUserInput",
            params: {
              threadId: "thread-ipc",
              turnId: "turn-ipc",
              itemId: "item-ipc",
              questions: [{ id: "q1", question: "Continue?" }],
            },
          }],
        },
      },
    },
  });

  const actionMessage = await waitForMessage(relayMessages, (message) => message.id === "req-ipc");
  assert.equal(actionMessage.method, "item/tool/requestUserInput");

  relaySocket.send(JSON.stringify({
    id: "req-ipc",
    result: {
      answers: {
        q1: { answers: ["Yes"] },
      },
    },
  }));

  const ipcReply = await waitForMessage(
    ipcFrames,
    (frame) => frame.method === "thread-follower-submit-user-input"
  );
  assert.deepEqual(ipcReply.params, {
    conversationId: "thread-ipc",
    requestId: "req-ipc",
    response: {
      answers: {
        q1: { answers: ["Yes"] },
      },
    },
  });
  assert.equal(fakeCodex.sent.some((message) => message.id === "req-ipc"), false);

  const resolvedMessage = await waitForMessage(
    relayMessages,
    (message) => message.method === "serverRequest/resolved"
      && message.params?.requestId === "req-ipc"
  );
  assert.equal(resolvedMessage.params.threadId, "thread-ipc");
});

test("bridge recovers desktop IPC state when the first live update is patch-only", async (t) => {
  const { tempDir, socketPath: ipcSocketPath } = createIpcTestSocket("remodex-bridge-ipc-recovery-");
  const relayServer = new WebSocket.Server({ port: 0 });
  const relayMessages = [];
  let relaySocket = null;
  let ipcServerSocket = null;
  let bridge = null;
  let fakeCodex = null;

  await new Promise((resolve) => relayServer.once("listening", resolve));
  relayServer.on("connection", (socket) => {
    relaySocket = socket;
    socket.on("message", (data) => {
      const parsed = safeParseJSON(data.toString("utf8"));
      if (parsed) {
        relayMessages.push(parsed);
      }
    });
  });

  const ipcServer = net.createServer((socket) => {
    ipcServerSocket = socket;
    attachFrameReader(socket, (frame) => {
      if (frame.method === "initialize") {
        writeFrame(socket, {
          type: "response",
          requestId: frame.requestId,
          resultType: "success",
          method: "initialize",
          handledByClientId: "desktop",
          result: { clientId: "desktop-test" },
        });
      }
    });
  });
  await new Promise((resolve) => ipcServer.listen(ipcSocketPath, resolve));

  const { startBridge } = loadBridgeWithTestDoubles({
    createCodexTransportImpl() {
      fakeCodex = createFakeCodexTransport({
        threadReadResult: {
          conversationState: {
            turns: [],
            requests: [{
              id: "req-recovered",
              method: "item/tool/requestUserInput",
              completed: true,
              params: {
                threadId: "thread-ipc-recovery",
                turnId: "turn-ipc-recovery",
                itemId: "item-ipc-recovery",
                questions: [{ id: "q1", question: "Continue?" }],
              },
            }],
          },
        },
      });
      return fakeCodex;
    },
  });

  t.after(() => {
    bridge?.stop();
    relaySocket?.close();
    relayServer.close();
    ipcServer.close();
    ipcServerSocket?.destroy();
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  bridge = startBridge({
    printPairingQr: false,
    config: {
      relayUrl: `ws://127.0.0.1:${relayServer.address().port}`,
      pushServiceUrl: "",
      pushPreviewMaxChars: 160,
      refreshEnabled: false,
      refreshDebounceMs: 1,
      keepMacAwakeEnabled: false,
      codexEndpoint: "",
      refreshCommand: "",
      codexBundleId: "",
      codexAppPath: "",
      desktopIpcSocketPath: ipcSocketPath,
    },
  });

  await waitFor(() => relaySocket && relaySocket.readyState === WebSocket.OPEN);
  relaySocket.send(JSON.stringify({
    id: "resume-for-recovery",
    method: "thread/resume",
    params: { threadId: "thread-ipc-recovery" },
  }));

  await waitFor(() => ipcServerSocket, 2_000);
  writeFrame(ipcServerSocket, {
    type: "broadcast",
    method: "thread-stream-state-changed",
    sourceClientId: "desktop",
    version: 1,
    params: {
      conversationId: "thread-ipc-recovery",
      change: {
        type: "patches",
        patches: [{
          op: "replace",
          path: ["requests", 0, "completed"],
          value: false,
        }],
      },
    },
  });

  const recoveredRequest = await waitForMessage(
    relayMessages,
    (message) => message.id === "req-recovered"
  );
  assert.equal(recoveredRequest.method, "item/tool/requestUserInput");
  assert.equal(
    fakeCodex.sent.some((message) => message.method === "thread/read"),
    true
  );
});

test("bridge forwards live desktop assistant deltas to the phone", async (t) => {
  const { tempDir, socketPath: ipcSocketPath } = createIpcTestSocket("remodex-bridge-ipc-delta-");
  const relayServer = new WebSocket.Server({ port: 0 });
  const relayMessages = [];
  let relaySocket = null;
  let ipcServerSocket = null;
  let bridge = null;
  let fakeCodex = null;

  await new Promise((resolve) => relayServer.once("listening", resolve));
  relayServer.on("connection", (socket) => {
    relaySocket = socket;
    socket.on("message", (data) => {
      const parsed = safeParseJSON(data.toString("utf8"));
      if (parsed) {
        relayMessages.push(parsed);
      }
    });
  });

  const ipcServer = net.createServer((socket) => {
    ipcServerSocket = socket;
    attachFrameReader(socket, (frame) => {
      if (frame.method === "initialize") {
        writeFrame(socket, {
          type: "response",
          requestId: frame.requestId,
          resultType: "success",
          method: "initialize",
          handledByClientId: "desktop",
          result: { clientId: "desktop-test" },
        });
      }
    });
  });
  await new Promise((resolve) => ipcServer.listen(ipcSocketPath, resolve));

  const { startBridge } = loadBridgeWithTestDoubles({
    createCodexTransportImpl() {
      fakeCodex = createFakeCodexTransport();
      return fakeCodex;
    },
  });

  t.after(() => {
    bridge?.stop();
    relaySocket?.close();
    relayServer.close();
    ipcServer.close();
    ipcServerSocket?.destroy();
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  bridge = startBridge({
    printPairingQr: false,
    config: {
      relayUrl: `ws://127.0.0.1:${relayServer.address().port}`,
      pushServiceUrl: "",
      pushPreviewMaxChars: 160,
      refreshEnabled: false,
      refreshDebounceMs: 1,
      keepMacAwakeEnabled: false,
      codexEndpoint: "",
      refreshCommand: "",
      codexBundleId: "",
      codexAppPath: "",
      desktopIpcSocketPath: ipcSocketPath,
    },
  });

  await waitFor(() => relaySocket && relaySocket.readyState === WebSocket.OPEN);
  relaySocket.send(JSON.stringify({
    id: "resume-from-phone-delta",
    method: "thread/resume",
    params: { threadId: "thread-ipc-delta" },
  }));

  await waitFor(() => ipcServerSocket, 2_000);
  writeFrame(ipcServerSocket, {
    type: "broadcast",
    method: "thread-stream-state-changed",
    sourceClientId: "desktop",
    version: 1,
    params: {
      conversationId: "thread-ipc-delta",
      change: {
        type: "snapshot",
        conversationState: {
          turns: [{
            id: "turn-ipc-delta",
            items: [{
              id: "assistant-ipc-delta",
              type: "assistant_message",
              text: "Hello",
            }],
          }],
        },
      },
    },
  });
  writeFrame(ipcServerSocket, {
    type: "broadcast",
    method: "thread-stream-state-changed",
    sourceClientId: "desktop",
    version: 1,
    params: {
      conversationId: "thread-ipc-delta",
      change: {
        type: "patches",
        patches: [{
          op: "replace",
          path: ["turns", 0, "items", 0, "text"],
          value: "Hello world",
        }],
      },
    },
  });

  const deltaMessage = await waitForMessage(
    relayMessages,
    (message) => message.method === "item/agentMessage/delta"
  );
  assert.deepEqual(deltaMessage.params, {
    threadId: "thread-ipc-delta",
    turnId: "turn-ipc-delta",
    itemId: "assistant-ipc-delta",
    delta: " world",
  });
});

// Loads bridge.js with plaintext test transports while leaving the production module untouched.
test("Unix daemon bridge initializes once and reuses the connection after phone reconnect", async (t) => {
  const relayServer = new WebSocket.Server({ port: 0 });
  await new Promise(resolve => relayServer.once("listening", resolve));
  let relaySocket;
  let fakeCodex;
  let bridge;
  const replies = [];
  relayServer.on("connection", socket => {
    relaySocket = socket;
    socket.on("message", data => replies.push(JSON.parse(data.toString())));
  });
  const { startBridge } = loadBridgeWithTestDoubles({
    createCodexTransportImpl() {
      fakeCodex = createFakeCodexTransport();
      return fakeCodex;
    },
  });
  t.after(() => {
    bridge?.stop();
    relaySocket?.close();
    relayServer.close();
  });
  bridge = startBridge({
    printPairingQr: false,
    config: {
      relayUrl: `ws://127.0.0.1:${relayServer.address().port}`,
      pushServiceUrl: "", pushPreviewMaxChars: 160,
      refreshEnabled: false, refreshDebounceMs: 1, keepMacAwakeEnabled: false,
      codexEndpoint: "ws+unix:///tmp/codex-test.sock:/",
      refreshCommand: "", codexBundleId: "", codexAppPath: "",
    },
  });
  await waitFor(() => relaySocket?.readyState === WebSocket.OPEN);
  const init = { id: "first-init", method: "initialize", params: { clientInfo: { name: "codexmobile_android", version: "test" } } };
  relaySocket.send(JSON.stringify(init));
  await waitFor(() => fakeCodex.sent.some(message => message.id === init.id));
  fakeCodex.emitMessage({ id: init.id, result: { userAgent: "test" } });
  assert.deepEqual((await waitForMessage(replies, message => message.id === init.id)).result, { userAgent: "test" });
  relaySocket.send(JSON.stringify({ ...init, id: "reconnect-init" }));
  assert.deepEqual((await waitForMessage(replies, message => message.id === "reconnect-init")).result, { bridgeManaged: true });
  assert.equal(fakeCodex.sent.filter(message => message.method === "initialize").length, 1);
});

test("bridge forwards official item pages, live events, and native unsupported errors without local indexing", async t => {
  const relayServer = new WebSocket.Server({ port: 0 });
  await new Promise(resolve => relayServer.once("listening", resolve));
  let socket, bridge, fake;
  const replies = [];
  const requests = [];
  relayServer.on("connection", value => { socket = value; value.on("message", data => replies.push(JSON.parse(data.toString()))); });
  const { startBridge } = loadBridgeWithTestDoubles({createCodexTransportImpl() {
    fake = createFakeCodexTransport();
    const send = fake.send.bind(fake);
    fake.send = raw => { send(raw); const m = JSON.parse(raw);
      if (m.method === "thread/items/list") requests.push(m);
    };
    return fake;
  }});
  t.after(() => { bridge?.stop(); socket?.close(); relayServer.close(); });
  bridge = startBridge({ printPairingQr: false, config: {
    relayUrl: `ws://127.0.0.1:${relayServer.address().port}`, pushServiceUrl: "", pushPreviewMaxChars: 160,
    refreshEnabled: false, refreshDebounceMs: 1, keepMacAwakeEnabled: false,
    codexEndpoint: "ws://test", refreshCommand: "", codexBundleId: "", codexAppPath: "",
  }});
  await waitFor(() => socket?.readyState === WebSocket.OPEN);
  socket.send(JSON.stringify({id:"page",method:"thread/items/list",params:{threadId:"thread",limit:30,sortDirection:"desc"}}));
  await waitFor(() => requests.length === 1);
  fake.emitMessage({method:"item/agentMessage/delta",params:{threadId:"thread",turnId:"t",itemId:"live",delta:"now"}});
  await waitForMessage(replies, r => r.method === "item/agentMessage/delta");
  assert.equal(replies.some(r => r.id === "page"), false);
  const result = {data:[{turnId:"t",item:{id:"question",type:"agentMessage",text:"",questions:[{title:"Choose",options:["A","B"]}]}}],nextCursor:"older+/=",backwardsCursor:"newer"};
  fake.emitMessage({id:requests[0].id,result});
  assert.deepEqual((await waitForMessage(replies,r=>r.id==="page")).result,result);
  socket.send(JSON.stringify({id:"old-host",method:"thread/items/list",params:{threadId:"thread",limit:30}}));
  await waitFor(() => requests.length === 2);
  const error = {code:-32601,message:"thread/items/list not supported",data:{type:"unsupported"}};
  fake.emitMessage({id:requests[1].id,error});
  assert.deepEqual((await waitForMessage(replies,r=>r.id==="old-host")).error,error);
  assert.equal(fake.sent.filter(r=>r.method==="thread/turns/list").length,0);
});

test("repeated phone account status reads never force credential rotation", async t => {
  const relayServer = new WebSocket.Server({ port: 0 });
  await new Promise(resolve => relayServer.once("listening", resolve));
  let socket, bridge, fake;
  let rotations = 0;
  let expired = false;
  const replies = [];
  relayServer.on("connection", value => {
    socket = value;
    value.on("message", data => replies.push(JSON.parse(data.toString())));
  });
  const { startBridge } = loadBridgeWithTestDoubles({ createCodexTransportImpl() {
    fake = createFakeCodexTransport();
    const send = fake.send.bind(fake);
    fake.send = raw => {
      send(raw);
      const request = JSON.parse(raw);
      if (!["account/read", "getAuthStatus"].includes(request.method)) return;
      if (request.params.refreshToken) rotations++;
      const result = request.method === "account/read"
        ? { account: expired ? null : { type: "chatgpt", email: "user@example.test", planType: "pro" }, requiresOpenaiAuth: true }
        : { authMethod: "chatgpt", authToken: expired ? null : "private-test-token", requiresOpenaiAuth: true };
      queueMicrotask(() => fake.emitMessage({ id: request.id, result }));
    };
    return fake;
  } });
  t.after(() => { bridge?.stop(); socket?.close(); relayServer.close(); });
  bridge = startBridge({ printPairingQr: false, config: {
    relayUrl: `ws://127.0.0.1:${relayServer.address().port}`, pushServiceUrl: "", pushPreviewMaxChars: 160,
    refreshEnabled: false, refreshDebounceMs: 1, keepMacAwakeEnabled: false,
    codexEndpoint: "ws://test", refreshCommand: "", codexBundleId: "", codexAppPath: "",
  } });
  await waitFor(() => socket?.readyState === WebSocket.OPEN);
  for (const [index, method] of ["account/status/read", "getAuthStatus", "account/status/read"].entries()) {
    expired = index === 2;
    const id = `account-read-${index}`;
    socket.send(JSON.stringify({ id, method, params: {} }));
    const response = await waitForMessage(replies, message => message.id === id);
    assert.equal(response.result.status, expired ? "expired" : "authenticated");
    assert.equal(response.result.email, expired ? null : "user@example.test");
    assert.equal(response.result.planType, expired ? null : "pro");
    assert.equal(JSON.stringify(response).includes("private-test-token"), false);
    assert.equal(rotations, 0, "Viewing account status must not request token rotation");
  }
  assert.equal(fake.sent.filter(request => request.method === "getAuthStatus").length, 3);
});

function loadBridgeWithTestDoubles({ createCodexTransportImpl }) {
  const bridgePath = require.resolve("../src/bridge");
  const originalLoad = Module._load;
  delete require.cache[bridgePath];
  Module._load = function loadWithBridgeDoubles(request, parent, isMain) {
    if (parent?.filename === bridgePath && request === "./codex-transport") {
      return { createCodexTransport: createCodexTransportImpl };
    }
    if (parent?.filename === bridgePath && request === "./secure-transport") {
      return { createBridgeSecureTransport: createPlaintextSecureTransport };
    }
    if (parent?.filename === bridgePath && request === "./secure-device-state") {
      return createSecureDeviceStateDouble();
    }
    if (parent?.filename === bridgePath && request === "./package-version-status") {
      return { createBridgePackageVersionStatusReader: () => async () => ({ bridgeVersion: "test" }) };
    }
    return originalLoad.call(this, request, parent, isMain);
  };

  try {
    return require("../src/bridge");
  } finally {
    Module._load = originalLoad;
    delete require.cache[bridgePath];
  }
}

// Uses plaintext relay messages so this test can focus on bridge routing, not encryption.
function createPlaintextSecureTransport() {
  return {
    createPairingPayload() {
      return { v: 1, expiresAt: Date.now() + 60_000 };
    },
    bindLiveSendWireMessage() {},
    handleIncomingWireMessage(message, { onApplicationMessage }) {
      onApplicationMessage(message);
      return true;
    },
    queueOutboundApplicationMessage(message, sendWireMessage) {
      sendWireMessage(message);
    },
  };
}

function createSecureDeviceStateDouble() {
  return {
    loadOrCreateBridgeDeviceState() {
      return {
        macDeviceId: "mac-test",
        macIdentityPublicKey: "mac-key-test",
        trustedPhones: {},
      };
    },
    rememberLastSeenPhoneAppVersion(deviceState) {
      return deviceState;
    },
    resolveBridgeRelaySession(deviceState) {
      return {
        sessionId: "session-test",
        deviceState,
      };
    },
  };
}

function createFakeCodexTransport({
  threadReadResult = {
    conversationState: {
      turns: [],
      requests: [],
    },
  },
} = {}) {
  const listeners = {};
  const sent = [];
  return {
    sent,
    describe() {
      return "fake codex app-server";
    },
    send(message) {
      const parsed = JSON.parse(message);
      sent.push(parsed);
      if (parsed.method === "thread/read") {
        listeners.message?.(JSON.stringify({
          id: parsed.id,
          result: threadReadResult,
        }));
      }
    },
    onMessage(handler) {
      listeners.message = handler;
    },
    onClose(handler) {
      listeners.close = handler;
    },
    onError(handler) {
      listeners.error = handler;
    },
    onStarted(handler) {
      listeners.started = handler;
      setImmediate(() => handler({ mode: "test" }));
    },
    shutdown() {
      this.emitClose();
    },
    emitMessage(message) {
      listeners.message?.(JSON.stringify(message));
    },
    emitClose() {
      listeners.close?.();
    },
  };
}

function attachFrameReader(socket, onFrame) {
  let buffer = Buffer.alloc(0);
  socket.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 4) {
      const frameLength = buffer.readUInt32LE(0);
      if (buffer.length < 4 + frameLength) {
        return;
      }

      const payload = buffer.slice(4, 4 + frameLength).toString("utf8");
      buffer = buffer.slice(4 + frameLength);
      onFrame(JSON.parse(payload));
    }
  });
}

function writeFrame(socket, payload) {
  const body = Buffer.from(JSON.stringify(payload), "utf8");
  const header = Buffer.alloc(4);
  header.writeUInt32LE(body.length, 0);
  socket.write(Buffer.concat([header, body]));
}

async function waitForMessage(messages, predicate, timeoutMs = 5_000) {
  await waitFor(() => messages.find(predicate), timeoutMs);
  return messages.find(predicate);
}

async function waitFor(predicate, timeoutMs = 5_000) {
  const startedAt = Date.now();
  while (!predicate()) {
    if (Date.now() - startedAt > timeoutMs) {
      throw new Error("Timed out waiting for condition");
    }
    await wait(5);
  }
}

function safeParseJSON(value) {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function createIpcTestSocket(prefix) {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  const socketPath = process.platform === "win32"
    ? `\\\\.\\pipe\\${path.basename(tempDir)}-ipc`
    : path.join(tempDir, "ipc.sock");
  return { tempDir, socketPath };
}

test("Codex completion invokes the bridge predictor before any phone connects or reads", async t => {
  const relayServer = new WebSocket.Server({ port: 0 });
  await new Promise(resolve => relayServer.once("listening", resolve));
  let fake, bridge;
  const { startBridge } = loadBridgeWithTestDoubles({ createCodexTransportImpl() {
    fake = createFakeCodexTransport();
    return fake;
  } });
  t.after(() => {
    bridge?.stop();
    for (const socket of relayServer.clients) socket.terminate();
    relayServer.close();
  });
  bridge = startBridge({ printPairingQr: false, config: {
    relayUrl: `ws://127.0.0.1:${relayServer.address().port}`, pushServiceUrl: "", pushPreviewMaxChars: 160,
    refreshEnabled: false, refreshDebounceMs: 1, keepMacAwakeEnabled: false,
    codexEndpoint: "ws://test", refreshCommand: "", codexBundleId: "", codexAppPath: "",
  } });
  fake.emitMessage({ method: "turn/completed", params: { threadId: "thread", turn: { id: "turn", status: "completed" } } });
  await waitFor(() => fake.sent.some(m => m.method === "getAuthStatus"));
  const auth = fake.sent.find(m => m.method === "getAuthStatus");
  assert.deepEqual(auth.params, { includeToken: true, refreshToken: false });
  fake.emitMessage({ id: auth.id, result: { authMethod: "apiKey" } });
});

test("authorization RPCs reach the bundled service through the normal bridge router", async t => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'remodex-auth-router-'));
  const claims = Buffer.from(JSON.stringify({iat:1700000000,exp:4102444800})).toString('base64url');
  const credential = account => JSON.stringify({auth_mode:'chatgpt',last_refresh:'2026-01-01T00:00:00Z',
    tokens:{account_id:account,access_token:'header.'+claims+'.signature',refresh_token:'synthetic-'+account}});
  fs.mkdirSync(path.join(home,'auth-profiles','1'),{recursive:true,mode:0o700});
  fs.writeFileSync(path.join(home,'auth.json'),credential('current'),{mode:0o600});
  fs.writeFileSync(path.join(home,'auth-profiles','1','auth.json'),credential('saved'),{mode:0o600});
  const relay = new WebSocket.Server({port:0});
  await new Promise(resolve=>relay.once('listening',resolve));
  let socket, bridge, fake;
  const replies=[];
  relay.on('connection', value=>{
    socket=value;
    value.on('message',data=>replies.push(JSON.parse(data.toString())));
  });
  const {startBridge}=loadBridgeWithTestDoubles({createCodexTransportImpl() {
    fake=createFakeCodexTransport();
    const send=fake.send.bind(fake);
    fake.send=raw=>{
      send(raw);
      const request=JSON.parse(raw);
      if(request.method==='config/read') {
        fake.emitMessage({id:request.id,result:{config:{cli_auth_credentials_store:'file'},
          layers:[{name:{type:'user',file:path.join(home,'config.toml')}}]}});
      }
      if(request.method==='getAuthStatus') fake.emitMessage({id:request.id,result:{authToken:'does-not-match'}});
    };
    return fake;
  }});
  t.after(()=>{bridge?.stop();socket?.close();relay.close();fs.rmSync(home,{recursive:true,force:true});});
  bridge=startBridge({printPairingQr:false,config:{relayUrl:'ws://127.0.0.1:'+relay.address().port,
    pushServiceUrl:'',refreshEnabled:false,keepMacAwakeEnabled:false,codexEndpoint:'',desktopIpcSocketPath:''}});
  await waitFor(()=>socket?.readyState===WebSocket.OPEN);
  let id=0;
  async function rpc(action, params={}) {
    const requestId='auth-'+(++id);
    socket.send(JSON.stringify({id:requestId,method:'account/profiles/'+action,params}));
    return waitForMessage(replies,reply=>reply.id===requestId);
  }
  const list=await rpc('list');
  assert.equal(list.result.managedBy,'remodex');
  const profile=list.result.profiles[0];
  const selected={profile:'1',revision:profile.revision};
  assert.equal((await rpc('check',selected)).result.credentialValidity,'not_verified');
  assert.equal((await rpc('status')).result.profiles[0].active,false);
  assert.equal((await rpc('refreshToken',{...selected,revision:'0:0'})).error.data.errorCode,'PROFILE_CHANGED');
  assert.equal((await rpc('import',{...selected,sourcePath:path.join(home,'auth.json')})).error.data.errorCode,'IMPORT_SOURCE_NOT_ALLOWED');
  const exported=await rpc('export',selected);
  assert.ok(exported.result.path.startsWith(path.join(home,'auth-profiles','maintenance')));
  assert.equal(fs.readFileSync(exported.result.path,'utf8'),credential('saved'));
  assert.ok(!JSON.stringify(replies).includes('synthetic-'));
  assert.ok(!JSON.stringify(replies).includes('header.'));
});
