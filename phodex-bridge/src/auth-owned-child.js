// A short-lived local Python worker owns the authorization lock. Its private
// pipes address only the stdio child already held by this bridge.
const { spawn } = require("node:child_process");
const { createInterface } = require("node:readline");

function executeOwnedWorker(command, args, control, mayRestart) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, [...args, "--bridge-child"], { stdio: ["ignore", "pipe", "pipe", "pipe", "pipe"] });
    let acknowledged = false;
    let stderr = "";
    const watchdog = setTimeout(() => {
      reject(new Error("Timed out waiting for the authorization worker"));
      child.kill();
    }, 240_000);
    child.stderr.setEncoding("utf8").on("data", chunk => { stderr = (stderr + chunk).slice(-4096); });
    const replies = createInterface({ input: child.stdout });
    replies.on("line", stdout => {
      if (!acknowledged) { acknowledged = true; resolve({ stdout }); }
    });
    const requests = createInterface({ input: child.stdio[4] });
    requests.on("line", async line => {
      let reply;
      try {
        const { method, params, timeout } = JSON.parse(line);
        if (method === "account/rateLimitResetCredit/consume" && !args.includes("reset-card")) throw new Error("Reset confirmation required");
        if (method === "child/restart" && !mayRestart) throw new Error("This read-only authorization operation cannot restart Codex");
        reply = { result: await control(method, params, timeout) };
      } catch (error) {
        reply = { error: { code: error.code || "OWNED_RUNTIME_FAILED", message: error.message, stack: error.stack } };
      }
      if (child.stdio[3].writable) child.stdio[3].write(JSON.stringify(reply) + "\n");
    });
    child.stdio[3].on("error", reject);
    child.on("error", reject);
    child.on("close", (code, signal) => {
      clearTimeout(watchdog);
      if (!acknowledged) reject(Object.assign(new Error(`Authorization worker exited before responding (${signal || code}): ${stderr}`), { code, signal }));
    });
  });
}

module.exports = { executeOwnedWorker };
