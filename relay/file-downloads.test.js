const test=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const {once}=require('node:events');
const {WebSocket}=require('ws');
const {createRelayServer}=require('./server');
const {createFileDownloads}=require('./file-downloads');
test('file proxy stays separate from chat and phone reconnects during a download', {timeout:15000},async t=>{
 let upstreamClosed=false;
 const upstream=http.createServer((req,res)=>{
  assert.equal(req.url,'/files/stream?offset=262144');assert.equal(req.headers.authorization,'Bearer synthetic-test-ticket');
  res.writeHead(200,{'Content-Type':'application/vnd.remodex.encrypted-stream'});res.write(Buffer.alloc(1024));
  const timer=setInterval(()=>res.write(Buffer.alloc(1024)),30);res.on('close',()=>{clearInterval(timer);upstreamClosed=true;});
 });
 await new Promise(r=>upstream.listen(0,'127.0.0.1',r));
 const {server,wss}=createRelayServer({fileOrigin:`http://127.0.0.1:${upstream.address().port}`});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const port=server.address().port;const sockets=[];
 t.after(async()=>{sockets.forEach(ws=>ws.terminate());wss.close();server.closeAllConnections();upstream.closeAllConnections();await Promise.all([new Promise(r=>server.close(r)),new Promise(r=>upstream.close(r))]);});
 async function connect(role){const ws=new WebSocket(`ws://127.0.0.1:${port}/relay/file-isolation-fixture`,{headers:{'x-role':role}});sockets.push(ws);await once(ws,'open');return ws;}
 const mac=await connect('mac');let phone=await connect('iphone');
 const abort=new AbortController();const download=await fetch(`http://127.0.0.1:${port}/files/stream?offset=262144`,{headers:{Authorization:'Bearer synthetic-test-ticket'},signal:abort.signal});assert.equal(download.status,200);
 let receive=once(phone,'message');mac.send('chat-during-download');assert.equal((await receive)[0].toString(),'chat-during-download');
 const closed=once(phone,'close');phone.close();await closed;phone=await connect('iphone');
 receive=once(phone,'message');mac.send('chat-after-reconnect');assert.equal((await receive)[0].toString(),'chat-after-reconnect');
 assert.equal((await fetch(`http://127.0.0.1:${port}/health`)).status,200);
 abort.abort();await new Promise(r=>setTimeout(r,100));assert(upstreamClosed);
 receive=once(phone,'message');mac.send('chat-after-file-cancel');assert.equal((await receive)[0].toString(),'chat-after-file-cancel');
});
test('file proxy refuses remote upstreams',()=>{assert.throws(()=>createFileDownloads('http://example.org'));});

test('video ranges preserve length and do not spend the reconnect API rate budget', {timeout:10000},async t=>{
 const {createFixedWindowRateLimiter}=require('./server');
 const upstream=http.createServer((req,res)=>{assert.equal(req.url,'/files/stream?offset=12&length=128');res.end('range');});
 await new Promise(r=>upstream.listen(0,'127.0.0.1',r));
 const {server,wss}=createRelayServer({fileOrigin:`http://127.0.0.1:${upstream.address().port}`,httpRateLimiter:createFixedWindowRateLimiter({maxRequests:1,windowMs:60000})});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const base=`http://127.0.0.1:${server.address().port}`;
 t.after(async()=>{wss.close();server.closeAllConnections();upstream.closeAllConnections();await Promise.all([new Promise(r=>server.close(r)),new Promise(r=>upstream.close(r))]);});
 for(let i=0;i<3;i++){const r=await fetch(base+'/files/stream?offset=12&length=128');assert.equal(r.status,200);await r.text();}
 assert.equal((await fetch(base+'/unmatched')).status,404);
 assert.equal((await fetch(base+'/unmatched')).status,429);
});
