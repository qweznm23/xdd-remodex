const fs = require('node:fs');
const http = require('node:http');
const { startBridge } = require('../phodex-bridge/src/bridge');
const QRCode = require('../phodex-bridge/node_modules/qrcode-terminal/vendor/QRCode');
const QRErrorCorrectLevel = require('../phodex-bridge/node_modules/qrcode-terminal/vendor/QRCode/QRErrorCorrectLevel');
const path = require('node:path');
const root = process.env.REMODEX_RUNTIME_DIR || path.resolve(__dirname, '../.local/runtime');
const stateDir = process.env.REMODEX_DEVICE_STATE_DIR || path.join(root, 'state');
fs.mkdirSync(stateDir, { recursive: true, mode: 0o700 });
const downloadPage = process.env.REMODEX_DOWNLOAD_PAGE_URL || 'https://github.com/qweznm23/xdd-remodex/releases/latest';
const relayLabel = process.env.REMODEX_RELAY || 'local relay';
const html = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let pairing = null;
let status = null;
function renderQr(payload) {
  const qr = new QRCode(-1, QRErrorCorrectLevel.M);
  qr.addData(JSON.stringify(payload));
  qr.make();
  const count = qr.getModuleCount();
  let cells = '';
  for (let y = 0; y < count; y++) for (let x = 0; x < count; x++) {
    if (qr.isDark(y, x)) cells += `<rect x="${x + 4}" y="${y + 4}" width="1" height="1"/>`;
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${count + 8} ${count + 8}" width="420" height="420" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="white"/><g fill="black">${cells}</g></svg>`;
}
http.createServer((req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  if (req.url === '/status') {
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify(status));
    return;
  }
  if (req.url !== '/') { res.writeHead(404); res.end(); return; }
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.end(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>XDD Remodex 配对</title><style>body{font:20px system-ui;text-align:center;background:#f4f6fa;margin:30px}main{background:white;max-width:650px;margin:auto;padding:24px;border-radius:16px}code{font-size:28px}svg{max-width:100%;height:auto}</style><main><h1>XDD Remodex</h1><p><a href="${html(downloadPage)}">下载 Android 客户端</a></p><p>在手机 Remodex 内使用扫码配对</p>${pairing ? renderQr(pairing.pairingPayload) : '<p>正在生成配对信息</p>'}<p>中继：${html(relayLabel)}</p><p>配对码：<code>${pairing?.pairingCode || ''}</code></p><p>二维码有效期：${pairing ? new Date(pairing.pairingPayload.expiresAt).toLocaleString('zh-CN', {timeZone:'Asia/Shanghai'}) : ''}</p><p id="status">正在读取桥接状态</p><p>工作目录：${html(process.cwd())}</p></main><script>async function refresh(){try{const s=await(await fetch('/status')).json();document.getElementById('status').textContent='桥接状态：'+(s?.connectionStatus||'启动中')}catch{document.getElementById('status').textContent='状态读取失败，请检查服务'}}refresh();setInterval(refresh,3000)</script></html>`);
// The pairing page must not keep a failed bridge alive and prevent systemd recovery.
}).listen(19092, '127.0.0.1').unref();
startBridge({
  printPairingQr: false,
  onPairingSession(value) {
    pairing = value;
    fs.writeFileSync(`${stateDir}/pairing.json`, JSON.stringify(value), {mode:0o600});
  },
  onBridgeStatus(value) {
    status = value;
    fs.writeFileSync(`${stateDir}/status.json`, JSON.stringify(value), {mode:0o600});
  }
});
