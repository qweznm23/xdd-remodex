// Test-only stdio Codex. The account snapshot changes only after this child is replaced.
const fs = require('node:fs');
const path = require('node:path');
const { createInterface } = require('node:readline');
const home = process.env.CODEX_HOME;
if (!path.basename(home).startsWith('remodex-owned-test-')) process.exit(1);
const auth = JSON.parse(fs.readFileSync(path.join(home, 'auth.json')));
createInterface({input: process.stdin}).on('line', line => {
  const msg = JSON.parse(line);
  if (!msg.id) return;
  let result = {};
  if (msg.method === 'getAuthStatus') result = {authToken:auth.tokens.access_token};
  if (msg.method === 'account/rateLimits/read') result = {rateLimits:{primary:{usedPercent:30,windowDurationMins:10080}}};
  process.stdout.write(JSON.stringify({id:msg.id,result})+'\n');
});
