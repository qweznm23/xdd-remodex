// Preserve official item cursors and errors. Reduce page size instead of silently dropping items.
async function fetchBoundedThreadItemsPage(request, { fetchPage, sanitize,
  targetBytes = 256 * 1024, maxBytes = 4 * 1024 * 1024 } = {}) {
  const params = request.params || {};
  let limit = Number.isInteger(params.limit) && params.limit > 0 ? Math.min(params.limit, 50) : 30;
  for (;;) {
    const result = await fetchPage({ ...params, limit });
    if (!Array.isArray(result?.data) || !(result.nextCursor === null || typeof result.nextCursor === "string") ||
        result.data.some(entry => typeof entry?.turnId !== "string" || !entry.turnId ||
          typeof entry.item?.id !== "string" || !entry.item.id || typeof entry.item.type !== "string")) {
      throw Object.assign(new Error("Invalid thread/items/list response"), { code: -32603, data: { stage: "item_page_decode" } });
    }
    const response = sanitize({ id: request.id, result });
    const bytes = Buffer.byteLength(JSON.stringify(response));
    if (bytes <= targetBytes || (result.data.length <= 1 && bytes <= maxBytes)) return response;
    if (limit <= 1 || result.data.length <= 1) {
      throw Object.assign(new Error("One history item exceeds the relay size limit"), {
        code: -32000, data: { stage: "item_page_size", bytes, maxBytes, itemId: result.data[0]?.item.id },
      });
    }
    limit = Math.max(1, Math.min(Math.floor(limit / 2), result.data.length - 1));
  }
}
module.exports = { fetchBoundedThreadItemsPage };
