// A loopback relay and real bridge transport. Stdin only controls the offline
// backlog and bridge restart; all Android protocol traffic traverses WebSocket.
const { generateKeyPairSync } = require('node:crypto');
const { once } = require('node:events');
const { createInterface } = require('node:readline');
const WebSocket = require('ws');
const { createBridgeSecureTransport } = require('../../src/secure-transport');
const { createRelayServer } = require('../../../relay/server');

async function main() {
  console.log = console.error;
  const key = generateKeyPairSync('ed25519').privateKey.export({ format: 'jwk' });
  let deviceState = {
    macDeviceId: 'test-bridge', macIdentityPrivateKey: Buffer.from(key.d, 'base64url').toString('base64'),
    macIdentityPublicKey: Buffer.from(key.x, 'base64url').toString('base64'), trustedPhones: {},
  };
  const { server } = createRelayServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const relayUrl = `ws://127.0.0.1:${server.address().port}/relay`;
  const sessionId = 'fixture-session';
  const mac = new WebSocket(`${relayUrl}/${sessionId}`, { headers: {
    'x-role': 'mac', 'x-mac-device-id': deviceState.macDeviceId,
    'x-mac-identity-public-key': deviceState.macIdentityPublicKey,
  } });
  await once(mac, 'open');
  const send = wire => { mac.send(wire); return true; };
  const start = () => createBridgeSecureTransport({
    sessionId, relayUrl, deviceState, persistTrustedPhone: false, onTrustedPhoneUpdate(state) { deviceState = state; },
  });
  let transport = start();
  mac.on('message', bytes => {
    const wire = bytes.toString();
    if (JSON.parse(wire).kind === 'resumeState') transport.bindLiveSendWireMessage(send);
    transport.handleIncomingWireMessage(wire, {
      sendControlMessage: message => send(JSON.stringify(message)),
      onApplicationMessage: text => {
        const request = JSON.parse(text);
        transport.queueOutboundApplicationMessage(JSON.stringify({ id: request.id, result: request.params }));
      },
    });
  });
  process.stdout.write(JSON.stringify(transport.createPairingPayload()) + '\n');
  for await (const line of createInterface({ input: process.stdin })) {
    const command = JSON.parse(line);
    if (command.fixture === 'restart') transport = start();
    else if (command.fixture !== 'backlog') throw new Error('Unknown test operation');
    transport.bindLiveSendWireMessage(null);
    for (let i = 0; i < (command.count ?? 0); i++) {
      transport.queueOutboundApplicationMessage(JSON.stringify({ method: 'turn/started', params: { threadId: 'old', turnId: 'old' } }));
    }
    process.stdout.write('ready\n');
  }
}
main().catch(error => { console.error(error); process.exit(1); });
