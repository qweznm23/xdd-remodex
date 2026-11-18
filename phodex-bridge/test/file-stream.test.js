const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const os=require('node:os');
const path=require('node:path');
const {createDecipheriv}=require('node:crypto');
const {createFileStreams,revisionOf}=require('../src/file-stream');
async function fixture(t, bytes=Buffer.alloc(700000,41)) {
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'remodex-stream-'));const file=path.join(dir,'file.bin');await fs.writeFile(file,bytes);
 const streams=createFileStreams();await new Promise(resolve=>streams.server.listen(0,'127.0.0.1',resolve));
 t.after(async()=>{await streams.close();await fs.rm(dir,{recursive:true,force:true});});
 const revision=revisionOf(await fs.stat(file));
 const ticket=streams.grant({path:file,revision,byteLength:bytes.length});
 const url=`http://127.0.0.1:${streams.server.address().port}/files/stream`;
 const get=(offset=0,token=ticket.token)=>fetch(url+'?offset='+offset,{headers:{Authorization:'Bearer '+token}});
 return {streams,file,bytes,ticket,get};
}
function decode(data,ticket,offset=0){
 let p=0;const output=[];let eof=false;
 while(p<data.length){const n=data.readUInt32BE(p);p+=4;const nonce=data.subarray(p,p+12);p+=12;const encrypted=data.subarray(p,p+n);p+=n;const tag=data.subarray(p,p+16);p+=16;
 const cipher=createDecipheriv('aes-256-gcm',Buffer.from(ticket.keyBase64,'base64'),nonce);cipher.setAAD(Buffer.from(`${ticket.token}:${ticket.revision}:${offset}`));cipher.setAuthTag(tag);const plain=Buffer.concat([cipher.update(encrypted),cipher.final()]);if(!n){eof=true;assert.equal(offset,ticket.byteLength);}else{assert(!eof);output.push(plain);offset+=n;}}
 assert(eof);return Buffer.concat(output);
}
test('streams binary with authenticated EOF and resumes at verified offset',async t=>{
 const f=await fixture(t);let r=await f.get();assert.equal(r.status,200);const wire=Buffer.from(await r.arrayBuffer());assert.deepEqual(decode(wire,f.ticket),f.bytes);assert(wire.length<f.bytes.length*1.01);
 r=await f.get(262144);assert.deepEqual(decode(Buffer.from(await r.arrayBuffer()),f.ticket,262144),f.bytes.subarray(262144));
 assert.equal((await f.get(0,'bad')).status,401);assert.equal((await f.get(f.bytes.length+1)).status,416);
 const corrupt=Buffer.from(wire);corrupt[25]^=1;assert.throws(()=>decode(corrupt,f.ticket));
});
test('changed files are rejected rather than silently mixed',async t=>{
 const f=await fixture(t);await fs.appendFile(f.file,'changed');const r=await f.get();assert.equal(r.status,409);assert.equal((await r.json()).code,'file_changed');
});
test('empty files require authenticated final frame',async t=>{const f=await fixture(t,Buffer.alloc(0));assert.equal(decode(Buffer.from(await(await f.get()).arrayBuffer()),f.ticket).length,0);});
test('many completed preview tickets do not exhaust capacity',async t=>{const f=await fixture(t);for(let i=0;i<100;i++)f.streams.grant({path:f.file,revision:f.ticket.revision,byteLength:f.bytes.length});});

test('bounded random-access ranges authenticate their own EOF and preserve the playback ticket', async t => {
 const f=await fixture(t,Buffer.alloc(2*1024*1024,17));
 const address=f.streams.server.address();
 async function range(offset,length) {return fetch(`http://127.0.0.1:${address.port}/files/stream?offset=${offset}&length=${length}`,{headers:{Authorization:'Bearer '+f.ticket.token}});}
 const start=123456,length=500000;
 const response=await range(start,length);assert.equal(response.status,200);
 const wire=Buffer.from(await response.arrayBuffer());
 assert.deepEqual(decode(wire,{...f.ticket,byteLength:start+length},start),f.bytes.subarray(start,start+length));
 assert(wire.length<length+100);
 assert.equal((await range(0,0)).status,416);assert.equal((await range(0,4194305)).status,416);
 const tail=await range(f.bytes.length-10,1024);assert.equal(decode(Buffer.from(await tail.arrayBuffer()),f.ticket,f.bytes.length-10).length,10);
});
