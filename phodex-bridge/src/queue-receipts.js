// Codex owns the queue and drains it without a phone. Its clientUserMessageId is
// a correlation ID, not an idempotency key. Keep a small private receipt per submission.
const fs = require("node:fs");
const path = require("node:path");
const { createHash, randomUUID } = require("node:crypto");
const hash = value => createHash("sha256").update(value).digest("hex");
const fault = (message, code) => Object.assign(new Error(message), { code: -32000, data: { errorCode: code } });

function createQueueReceipts(directory, request, prepareInput = async input => input) {
  const active = new Map();
  function withCleanup(work, cleanup) {
    let primary;
    try { return work(); }
    catch (error) { primary = error; throw error; }
    finally {
      try { cleanup(); } catch (error) {
        if (!primary) throw error;
        (primary.suppressed ??= []).push(error);
      }
    }
  }

  function write(file, value, exclusive) {
    const temp = file + "." + randomUUID();
    const fd = fs.openSync(temp, "wx", 0o600);
    withCleanup(() => {
      withCleanup(() => {
        fs.writeFileSync(fd, JSON.stringify(value));
        fs.fsyncSync(fd);
      }, () => fs.closeSync(fd));
      if (exclusive) fs.linkSync(temp, file);
      else fs.renameSync(temp, file);
      const parent = fs.openSync(directory, "r");
      withCleanup(() => fs.fsyncSync(parent), () => fs.closeSync(parent));
    }, () => fs.rmSync(temp, { force: true }));
  }

  function discardRejected(file, error) {
    try { fs.unlinkSync(file); error.notSubmitted = true; }
    catch (cleanup) { (error.suppressed ??= []).push(cleanup); }
  }

  async function add(params) {
    const {threadId, clientUserMessageId, input} = params || {};
    if (typeof threadId !== "string" || !threadId.trim() || typeof clientUserMessageId !== "string" ||
        !/^[a-f0-9-]{36}$/i.test(clientUserMessageId) || !Array.isArray(input) || !input.length) {
      throw fault("Invalid queue submission", "QUEUE_INVALID");
    }
    const key = hash(JSON.stringify([threadId, clientUserMessageId]));
    const signature = hash(JSON.stringify(input));
    if (active.has(key)) {
      const previous = active.get(key);
      if (previous.signature !== signature) throw fault("Queue ID reused for different input", "QUEUE_CONFLICT");
      return previous.task;
    }
    const task = submit(key, signature, {threadId, clientUserMessageId, input});
    active.set(key, {signature, task});
    try { return await task; } finally { active.delete(key); }
  }

  async function submit(key, signature, params) {
    fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
    const file = path.join(directory, key + ".json");
    try { write(file, { signature }, true); }
    catch (err) {
      if (err.code !== "EEXIST") throw err;
      const record = JSON.parse(fs.readFileSync(file, "utf8"));
      if (record.cancelled) throw fault("Submission was withdrawn", "QUEUE_CANCELLED");
      if (record.signature !== signature) throw fault("Queue ID reused for different input", "QUEUE_CONFLICT");
      if (record.id) return { queuedSubmissionId: record.id };
      return recoverReceipt(file, record, params);
    }
    let input;
    try { input = await prepareInput(params.input); }
    catch (error) { discardRejected(file, error); throw error; }
    let result;
    try { result = await request("thread/queue/add", { ...params, input }); }
    catch (error) {
      // Only an explicit rejection authorizes another submit. A timeout can arrive after acceptance.
      if (Number.isInteger(error.code)) discardRejected(file, error);
      throw error;
    }
    const id = result?.queuedSubmission?.id;
    if (!id) throw fault("Codex did not return a queued submission ID", "QUEUE_INVALID_RESPONSE");
    write(file, { signature, id }, false);
    return { queuedSubmissionId: id };
  }
  async function recoverReceipt(file, record, params) {
    if (record.id) return { queuedSubmissionId: record.id };
    let cursor = null;
    const visited = new Set();
    do {
      const page = await request("thread/queue/list", { threadId: params.threadId, cursor, limit: 16 });
      const item = page.data.find(row => row.clientUserMessageId === params.clientUserMessageId);
      if (item) {
        write(file, { signature: record.signature, id: item.id }, false);
        return { queuedSubmissionId: item.id };
      }
      cursor = page.nextCursor;
      if (cursor && visited.has(cursor)) throw fault("Repeated native queue cursor", "QUEUE_CURSOR");
      visited.add(cursor);
    } while (cursor);
    throw fault("Cannot confirm earlier submission. Check conversation history before sending again.", "QUEUE_UNCONFIRMED");
  }

  // Withdrawing an unacknowledged message must never submit it just to obtain its server ID.
  async function cancelPending({ threadId, clientUserMessageId }) {
    if (!threadId || !/^[a-f0-9-]{36}$/i.test(clientUserMessageId)) throw fault("Invalid submission ID", "QUEUE_INVALID");
    const key = hash(JSON.stringify([threadId, clientUserMessageId]));
    const file = path.join(directory, key + ".json");
    fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
    const pending = active.get(key);
    if (pending) {
      try { await pending.task; } catch (failure) { if (!failure.notSubmitted) throw failure; }
    }
    try {
      write(file, { cancelled: true }, true);
      return { deleted: true };
    } catch (err) { if (err.code !== "EEXIST") throw err; }
    const record = JSON.parse(fs.readFileSync(file, "utf8"));
    if (record.cancelled) return { deleted: true };
    const { queuedSubmissionId } = await recoverReceipt(file, record, {threadId, clientUserMessageId});
    const result = await request("thread/queue/delete", { threadId, queuedSubmissionId });
    if (result.deleted === true) write(file, { cancelled: true }, false);
    return result;
  }
  return { add, cancelPending };
}
module.exports = { createQueueReceipts };
