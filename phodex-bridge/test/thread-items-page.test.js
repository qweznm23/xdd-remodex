const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const { fetchBoundedThreadItemsPage } = require("../src/thread-items-page");
const { sanitizeThreadItemsForRelay } = require("../src/bridge");
const entry = (id, size = 10) => ({ turnId: "turn", item: { id, type: "agentMessage", text: "x".repeat(size) } });
const sanitize = response => sanitizeThreadItemsForRelay(response, "thread");

test("official item pages retain order/cursors/questions/images and never read a JSONL index", async () => {
  const result = { data: [entry("last"),
    { turnId: "turn", item: { id: "question", type: "agentMessage", text: "", questions: [{title: "请选择", options: ["甲","乙"]}] } },
    { turnId: "turn", item: { id: "image", type: "imageView", path: "/tmp/image.png" } }],
    nextCursor: "opaque+older/=", backwardsCursor: "newer" };
  const read = fs.readFileSync;
  fs.readFileSync = () => { throw new Error("Unexpected full file read"); };
  try {
    const response = await fetchBoundedThreadItemsPage({ id: "req", params: { threadId: "thread", cursor: "opaque", sortDirection: "desc", limit: 30 } }, {
      fetchPage: async params => { assert.equal(params.cursor, "opaque"); assert.equal(params.limit, 30); return result; }, sanitize,
    });
    assert.deepEqual(response.result, result);
  } finally { fs.readFileSync = read; }
});

test("oversized page is refetched from the same cursor with fewer complete items", async () => {
  const limits = [];
  const response = await fetchBoundedThreadItemsPage({ id: 1, params: { threadId: "t", cursor: "start", limit: 8 } }, {
    targetBytes: 500, maxBytes: 1000, sanitize,
    fetchPage: async params => { limits.push(params.limit); assert.equal(params.cursor, "start");
      return { data: Array.from({length:params.limit}, (_, n) => entry(`${n}`, 150)), nextCursor: `after-${params.limit}` }; },
  });
  assert.deepEqual(limits, [8,4,2]);
  assert.equal(response.result.nextCursor, "after-2");
  assert.equal(response.result.data.length, 2);
  assert.equal(response.result.data[0].item.text.length, 150);
});

test("native rejection and network failures retain exact cause without retry/fallback", async () => {
  for (const failure of [Object.assign(new Error("Unknown method"), {code:-32601,data:{type:"unsupported"}}), new Error("connection reset", {cause:new Error("socket")})]) {
    let calls = 0;
    await assert.rejects(fetchBoundedThreadItemsPage({id:1,params:{}}, {
      fetchPage: async () => { calls++; throw failure; }, sanitize,
    }), error => error === failure);
    assert.equal(calls, 1);
  }
});

test("malformed responses and overlarge individual items remain explicit errors", async () => {
  await assert.rejects(fetchBoundedThreadItemsPage({id:1,params:{}}, {
    fetchPage:async()=>({data:[]}),sanitize,
  }), error => error.code === -32603 && error.data.stage === "item_page_decode");
  await assert.rejects(fetchBoundedThreadItemsPage({id:1,params:{}}, {
    fetchPage:async()=>({data:[entry("huge",2000)],nextCursor:"more"}),sanitize,targetBytes:500,maxBytes:1000,
  }), error => error.data.stage === "item_page_size" && error.data.itemId === "huge");
});
