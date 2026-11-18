const https = require("node:https");
const { createHash } = require("node:crypto");

const { createPredictionCache } = require("./composer-prediction-cache");

const configUrl = "https://chatgpt.com/backend-api/wham/predictions/config";
const agent = new https.Agent({ keepAlive: true, proxyEnv: process.env });

async function readPredictionConfig(token, accountId) {
  const headers = { Authorization: `Bearer ${token}` };
  if (accountId) headers["ChatGPT-Account-ID"] = accountId;
  return new Promise((resolve, reject) => {
    const req = https.get(configUrl, { headers, agent, signal: AbortSignal.timeout(10_000) }, (res) => {
      res.on("error", reject);
      if (res.statusCode !== 200) {
        res.resume();
        reject(new Error(`Prediction configuration HTTP ${res.statusCode}`));
        return;
      }
      const chunks = [];
      let size = 0;
      res.on("data", (chunk) => {
        size += chunk.length;
        if (size > 128 * 1024) res.destroy(new Error("Prediction config exceeds 128 KiB"));
        else chunks.push(chunk);
      });
      res.on("end", () => {
        try { resolve(JSON.parse(Buffer.concat(chunks).toString("utf8"))); }
        catch (cause) { reject(new Error("Invalid prediction config JSON", { cause })); }
      });
    });
    req.on("error", reject);
  });
}

function createComposerPredictor(sendRequest, directory, readConfig = readPredictionConfig, random = Math.random) {
  let cached;
  const disk = createPredictionCache(directory);
  const inFlight = new Map();
  const work = new Set();
  let authRead;
  let sequence = 0;

  async function predict({ threadId, sourceTurnId, trigger }) {
    // Register before the first await, so even a start during the auth RPC supersedes this turn.
    const job = { threadId, sourceTurnId, order: ++sequence, superseded: false };
    work.add(job);
    const skip = () => ({ sourceTurnId, text: null });
    let stage = "validate";
    try {
      if (typeof threadId !== "string" || !threadId || typeof sourceTurnId !== "string" || !sourceTurnId ||
          !["live", "catch_up"].includes(trigger)) throw new Error("Invalid composer prediction request");
      stage = "getAuthStatus";
      authRead ??= sendRequest(stage, { includeToken: true, refreshToken: false }).finally(() => { authRead = null; });
      const auth = await authRead;
      if (!["chatgpt", "chatgptAuthTokens"].includes(auth?.authMethod) || !auth.authToken) return skip();
      const payload = auth.authToken.split(".")[1];
      if (!payload) return skip();
      let claims;
      try { claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")); }
      catch (cause) {
        const frames = cause.stack?.split("\n").filter(line => /^\s+at /.test(line)) ?? [];
        const offset = cause.message.match(/position (\d+)/)?.[1];
        cause.message = `Invalid credential claims${offset ? ` at position ${offset}` : ""} (contents redacted)`;
        cause.stack = [`${cause.name}: ${cause.message}`, ...frames].join("\n");
        throw cause;
      }
      if (!["https://auth.openai.com", "https://auth0.openai.com/"].includes(claims.iss)) return skip();
      const account = claims["https://api.openai.com/auth"]?.chatgpt_account_id;
      if (!account && !claims.sub) return skip();
      const key = createHash("sha256").update(JSON.stringify([claims.iss, account, claims.sub, threadId])).digest("hex");
      job.key = key;
      const sampleKey = JSON.stringify([key, sourceTurnId]);
      if (job.superseded) return skip();
      const previous = inFlight.get(sampleKey);
      if (previous) return await previous;
      const result = lookup();
      inFlight.set(sampleKey, result);
      try { return await result; }
      finally { if (inFlight.get(sampleKey) === result) inFlight.delete(sampleKey); }

      async function lookup() {
        stage = "prediction-cache/read";
        const stored = await disk.read(key, sourceTurnId);
        if (job.superseded) return skip();
        if (stored) return stored;
        stage = "thread/turns/list";
        const page = await sendRequest(stage, { threadId, sortDirection: "desc", limit: 1, itemsView: "notLoaded" });
        if (!Array.isArray(page?.data)) throw new Error("Invalid latest turn response");
        // A stale/mismatched request must never replace the latest result with an empty entry.
        if (job.superseded || page.data[0]?.id !== sourceTurnId || page.data[0].status !== "completed") return skip();
        // A new completion can arrive even when we missed its start. An earlier RPC
        // must not replace this verified turn if it finishes after our sample.
        for (const older of work) {
          if (older.key === key && older.sourceTurnId !== sourceTurnId && older.order < job.order) older.superseded = true;
        }
        stage = "predictions/config";
        const tokenKey = createHash("sha256").update(auth.authToken).digest("hex");
        if (cached?.key !== tokenKey || cached.expires < Date.now()) {
          const pending = readConfig(auth.authToken, account);
          cached = { key: tokenKey, expires: Date.now() + 300_000, pending };
          pending.catch(() => { if (cached?.pending === pending) cached = null; });
        }
        const config = await cached.pending;
        if (job.superseded) return skip();
        if (typeof config?.is_enabled !== "boolean" || (config.is_enabled && !config.prompt?.trim())) {
          throw new Error("Invalid prediction configuration");
        }
        const result = config.is_enabled ? await sample(config, { threadId, sourceTurnId, trigger }, job) : skip();
        if (job.superseded) return skip();
        stage = "prediction-cache/write";
        await disk.write(key, result, () => !job.superseded);
        return job.superseded ? skip() : result;
      }
    } catch (cause) {
      if (cause.predictionStage) throw cause;
      throw predictionError(stage, cause);
    } finally { work.delete(job); }
  }

  function observe(message) {
    if (message?.id != null || !["turn/started", "turn/completed", "turn/failed"].includes(message?.method)) return;
    const params = message.params;
    const threadId = params?.threadId ?? params?.thread_id ?? params?.turn?.threadId;
    const turnId = params?.turn?.id ?? params?.turnId ?? params?.turn_id ?? params?.id;
    if (!threadId) return;
    const status = params?.turn?.status ?? params?.status;
    if (message.method === "turn/started" || message.method === "turn/failed" || (status && status !== "completed")) {
      for (const job of work) {
        if (job.threadId === threadId && (!turnId ||
            (message.method === "turn/started" ? job.sourceTurnId !== turnId : job.sourceTurnId === turnId))) job.superseded = true;
      }
      return;
    }
    if (turnId) return predict({ threadId, sourceTurnId: turnId, trigger: "live" });
  }

  return { read: predict, observe };

  async function sample(config, { threadId, sourceTurnId, trigger }, job) {
    const skip = () => ({ sourceTurnId, text: null });
    let stage = "sample-rate";
    try {
      const rate = config[trigger === "live" ? "live_sample_rate" : "catch_up_sample_rate"] ?? 1;
      if (!Number.isFinite(rate) || rate < 0 || rate > 1) throw new Error("Invalid prediction sample rate");
      if (random() >= rate) return skip();
      stage = "thread/resume";
      const parent = await sendRequest(stage, { threadId, excludeTurns: true });
      if (job.superseded || parent?.modelProvider !== "openai" || parent.collaborationMode?.mode === "plan" ||
          config.unsupported_models?.includes(parent.model)) return skip();
      stage = "thread/sample";
      const schema = { type: "object", properties: { suggestion: { anyOf: [{ type: "string" }, { type: "null" }] } },
        required: ["suggestion"], additionalProperties: false };
      const result = await sendRequest(stage, {
        threadId, expectedTurnId: sourceTurnId, threadSource: "composer_predictions", turnTrigger: "composer_predictions",
        prompt: `${config.prompt}\n\nReturn only JSON matching this schema: ${JSON.stringify(schema)}.`,
        effort: config.prediction_reasoning_effort || null, disableMcp: true,
        responsesapiClientMetadata: { prediction_source_thread_id: threadId, prediction_source_turn_id: sourceTurnId, prediction_trigger: trigger },
      }, 90_000);
      stage = "decode-sample";
      if (result?.sourceTurnId !== sourceTurnId || typeof result.text !== "string") throw new Error("Missing or mismatched sample response");
      const { suggestion } = JSON.parse(result.text);
      if (suggestion !== null && typeof suggestion !== "string") throw new Error("Invalid suggestion field");
      return { sourceTurnId, text: suggestion?.trim() || null };
    } catch (cause) {
      throw predictionError(stage, cause);
    }
  }
}

function predictionError(stage, cause) {
  return Object.assign(new Error(`Composer prediction failed at ${stage}: ${cause.message}`, { cause }), {
    errorCode: "composer_prediction_failed", predictionStage: stage, upstreamCode: cause.code,
  });
}
module.exports = { createComposerPredictor };
