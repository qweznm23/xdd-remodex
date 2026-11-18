const test = require("node:test");
const assert = require("node:assert/strict");
const { createComposerPredictor } = require("../src/composer-prediction");

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const temp = fs.mkdtempSync(path.join(os.tmpdir(), "remodex-prediction-test-"));
test.after(() => fs.rmSync(temp, { recursive: true, force: true }));
const source = { threadId: "thread-a", sourceTurnId: "turn-a", trigger: "catch_up" };
const claims = { iss: "https://auth.openai.com", "https://api.openai.com/auth": { chatgpt_account_id: "a" } };
const token = (suffix) => ["h", Buffer.from(JSON.stringify(claims)).toString("base64url"), suffix].join(".");
function fixture(config = {}, overrides = {}, directory = fs.mkdtempSync(path.join(temp, "cache-"))) {
  const calls = [];
  let configReads = 0;
  const configFor = async () => {
    configReads++;
    return { is_enabled: true, prompt: "Next user message", unsupported_models: [], ...config };
  };
  const responses = {
    getAuthStatus: { authMethod: "chatgpt", authToken: token("first") },
    "thread/turns/list": { data: [{ id: source.sourceTurnId, status: "completed" }] },
    "thread/resume": { modelProvider: "openai", model: "model" },
    "thread/sample": { sourceTurnId: source.sourceTurnId, text: '{"suggestion":"  Review the change  "}' },
    ...overrides,
  };
  const request = async (method, params) => {
    calls.push({ method, params });
    const result = responses[method];
    if (result instanceof Error) throw result;
    return typeof result === "function" ? result(params) : result;
  };
  const predictor = createComposerPredictor(request, directory, configFor, () => 0.5);
  return { predict: predictor.read, observe: predictor.observe, directory, calls, responses, configReads: () => configReads };
}

test("uses the configured prediction flow and returns just a source-scoped editable suggestion", async () => {
  const { predict, calls, configReads } = fixture();
  assert.deepEqual(await predict(source), { sourceTurnId: "turn-a", text: "Review the change" });
  assert.deepEqual(calls.map(c => c.method), ["getAuthStatus", "thread/turns/list", "thread/resume", "thread/sample"]);
  assert.equal(calls[0].params.refreshToken, false);
  const sample = calls.at(-1).params;
  assert.equal(sample.expectedTurnId, source.sourceTurnId);
  assert.equal(sample.disableMcp, true);
  assert.equal(sample.threadSource, "composer_predictions");
  assert.equal(sample.responsesapiClientMetadata.prediction_trigger, "catch_up");
  assert.equal(configReads(), 1);
});

test("skips disabled accounts, excluded models, plan mode, changed turns and unsampled catch-up", async () => {
  for (const [config, overrides] of [
    [{ is_enabled: false }, {}], [{ catch_up_sample_rate: 0.1 }, {}],
    [{ unsupported_models: ["model"] }, {}],
    [{}, { "thread/resume": { modelProvider: "openai", collaborationMode: { mode: "plan" } } }],
    [{}, { "thread/turns/list": { data: [{ id: source.sourceTurnId, status: "interrupted" }] } }],
    [{}, { "thread/turns/list": { data: [{ id: "new-turn", status: "completed" }] } }],
  ]) {
    const { predict, calls } = fixture(config, overrides);
    assert.deepEqual(await predict(source), { sourceTurnId: source.sourceTurnId, text: null });
    assert.ok(!calls.some(c => c.method === "thread/sample"));
  }
});

test("keeps RPC failure stage, code, and raw cause; rejects malformed or stale samples", async () => {
  const cause = Object.assign(new Error("sample source changed"), { code: -32000 });
  await assert.rejects(fixture({}, { "thread/sample": cause }).predict(source), e =>
    e.cause === cause && e.upstreamCode === -32000 && e.predictionStage === "thread/sample");
  for (const result of [{ sourceTurnId: "other", text: '{"suggestion":"wrong"}' }, { sourceTurnId: "turn-a", text: "{}" }]) {
    await assert.rejects(fixture({}, { "thread/sample": result }).predict(source), e => e.predictionStage === "decode-sample");
  }
});

test("token rotation keeps a terminal skip for the same account", async () => {
  const { predict, configReads, responses } = fixture({}, { "thread/sample": { sourceTurnId: "turn-a", text: '{"suggestion":null}' } });
  assert.equal((await predict(source)).text, null);
  await predict(source);
  assert.equal(configReads(), 1);
  responses.getAuthStatus.authToken = token("next");
  await predict(source);
  assert.equal(configReads(), 1);
});

test("a reconnect or concurrent live and catch-up requests reuse the same sample", async () => {
  const { predict, calls } = fixture();
  const [a, b] = await Promise.all([predict(source), predict({ ...source, trigger: "live" })]);
  assert.deepEqual(a, b);
  assert.deepEqual(await predict(source), a);
  assert.equal(calls.filter(c => c.method === "thread/sample").length, 1);
});

const completion = (turn = "turn-a") => ({
  method: "turn/completed", params: { threadId: source.threadId, turn: { id: turn, status: "completed" } },
});

test("offline completion prepares a durable result; a fresh bridge serves it without config or sampling", async () => {
  const bridge = fixture();
  await bridge.observe(completion());
  const reopened = fixture({}, {}, bridge.directory);
  assert.equal((await reopened.predict(source)).text, "Review the change");
  assert.deepEqual(reopened.calls.map(call => call.method), ["getAuthStatus"]);
  assert.equal(reopened.configReads(), 0);
  assert.equal(bridge.calls.find(call => call.method === "thread/sample").params.responsesapiClientMetadata.prediction_trigger, "live");
  const file = path.join(bridge.directory, fs.readdirSync(bridge.directory)[0]);
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
});

test("explicit skip survives restart, accounts with a common thread are isolated", async () => {
  const bridge = fixture({ live_sample_rate: 0 });
  await bridge.observe(completion());
  const reopened = fixture({}, {}, bridge.directory);
  assert.equal((await reopened.predict(source)).text, null);
  assert.equal(reopened.configReads(), 0);
  const other = { ...claims, "https://api.openai.com/auth": { chatgpt_account_id: "other" } };
  reopened.responses.getAuthStatus.authToken = "h." + Buffer.from(JSON.stringify(other)).toString("base64url") + ".s";
  assert.equal((await reopened.predict(source)).text, "Review the change");
  assert.equal(reopened.configReads(), 1);
});

test("opening while offline-triggered work is in flight joins it; a newer completion supersedes it", async () => {
  const bridge = fixture();
  let unblock;
  let started;
  const sampling = new Promise(resolve => { started = resolve; });
  bridge.responses["thread/sample"] = ({ expectedTurnId }) => {
    const result = { sourceTurnId: expectedTurnId, text: JSON.stringify({ suggestion: expectedTurnId }) };
    if (expectedTurnId === "turn-a") { started(); return new Promise(resolve => { unblock = () => resolve(result); }); }
    return result;
  };
  const live = bridge.observe(completion());
  await sampling;
  const opening = bridge.predict(source);
  bridge.responses["thread/turns/list"] = { data: [{ id: "turn-b", status: "completed" }] };
  await bridge.observe(completion("turn-b"));
  unblock();
  assert.equal((await live).text, null);
  assert.equal((await opening).text, null);
  assert.equal(bridge.calls.filter(c => c.method === "thread/sample").length, 2);
  const reopened = fixture({}, {}, bridge.directory);
  assert.equal((await reopened.predict({ ...source, sourceTurnId: "turn-b" })).text, "turn-b");
  assert.equal(reopened.configReads(), 0);
  assert.equal(fs.readdirSync(bridge.directory).length, 1);
});

test("failed or interrupted turns never sample; a missed event uses catch-up once", async () => {
  const bridge = fixture();
  await bridge.observe({ method: "turn/failed", params: completion().params });
  await bridge.observe({ method: "turn/completed", params: { threadId: source.threadId, turn: { id: source.sourceTurnId, status: "interrupted" } } });
  assert.equal(bridge.calls.length, 0);
  await bridge.predict(source);
  await bridge.observe(completion());
  const samples = bridge.calls.filter(call => call.method === "thread/sample");
  assert.equal(samples.length, 1);
  assert.equal(samples[0].params.responsesapiClientMetadata.prediction_trigger, "catch_up");
});

test("cache I/O failure retains the error code and stage and allows recovery", async () => {
  const bad = path.join(temp, "unusable");
  fs.writeFileSync(bad, "regular file");
  const bridge = fixture({}, {}, bad);
  await assert.rejects(bridge.predict(source), err =>
    err.predictionStage === "prediction-cache/read" && err.upstreamCode === "ENOTDIR" && err.cause?.code === "ENOTDIR");
  fs.unlinkSync(bad);
  assert.equal((await bridge.predict(source)).text, "Review the change");
});
