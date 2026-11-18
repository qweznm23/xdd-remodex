const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const os=require('node:os');
const net=require('node:net');
const {execFileSync}=require('node:child_process');
const {createDecipheriv}=require('node:crypto');
test('paired preparation, UTF8 pagination, Linux thumbnail cache, and encrypted stream interoperate',async t=>{
 const socket=net.createServer();await new Promise(resolve=>socket.listen(0,'127.0.0.1',resolve));const port=socket.address().port;await new Promise(resolve=>socket.close(resolve));
 process.env.REMODEX_FILE_STREAM_PORT=String(port);
 const {handleWorkspaceMethod}=require('../src/workspace-handler');
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'remodex-workspace-stream-'));
 t.after(()=>fs.rm(dir,{recursive:true,force:true}));
 const text='中文🙂abc\n'.repeat(16000);await fs.writeFile(path.join(dir,'text.txt'),text);
 const info=await handleWorkspaceMethod('workspace/fileInfo',{cwd:dir,path:'text.txt'});assert(info.binaryStream);assert.equal(info.previewKind,'text');
 async function read(ticket){
  const response=await fetch(`http://127.0.0.1:${port}/files/stream`,{headers:{Authorization:'Bearer '+ticket.token}});assert.equal(response.status,200);
  const data=Buffer.from(await response.arrayBuffer());let p=0,offset=0;const out=[];let eof=false;
  while(p<data.length){const n=data.readUInt32BE(p);p+=4;const nonce=data.subarray(p,p+12);p+=12;const enc=data.subarray(p,p+n);p+=n;const tag=data.subarray(p,p+16);p+=16;
   const cipher=createDecipheriv('aes-256-gcm',Buffer.from(ticket.keyBase64,'base64'),nonce);cipher.setAAD(Buffer.from(`${ticket.token}:${ticket.revision}:${offset}`));cipher.setAuthTag(tag);const bytes=Buffer.concat([cipher.update(enc),cipher.final()]);out.push(bytes);offset+=bytes.length;if(!n)eof=true;
  }assert(eof);assert.equal(offset,ticket.byteLength);return Buffer.concat(out);
 }
 let offset=0;let result='';while(offset<info.byteLength){const page=await handleWorkspaceMethod('workspace/prepareTransfer',{cwd:dir,path:'text.txt',mode:'text',revision:info.revision,offset});result+=new TextDecoder('utf8',{fatal:true}).decode(await read(page));assert(page.nextOffset>offset);offset=page.nextOffset;}assert.equal(result,text);
 const original=path.join(dir,'big.png');execFileSync('/usr/bin/python3',['-c',"from PIL import Image; import sys; Image.new('RGB',(3000,2000),(70,140,220)).save(sys.argv[1])",original]);
 const image=await handleWorkspaceMethod('workspace/prepareTransfer',{cwd:dir,path:original,mode:'image',maxPixelDimension:512});const jpeg=await read(image);assert.equal(jpeg.readUInt16BE(0),0xffd8);await fs.writeFile(path.join(dir,'preview.jpg'),jpeg);
 const dimensions=execFileSync('/usr/bin/python3',['-c',"from PIL import Image;import sys;im=Image.open(sys.argv[1]);print(max(im.size))",path.join(dir,'preview.jpg')],{encoding:'utf8'});assert.equal(Number(dimensions),512);
 await assert.rejects(handleWorkspaceMethod('workspace/prepareTransfer',{cwd:dir,path:'/etc/passwd',mode:'download'}),e=>e.errorCode==='file_path_not_allowed');
 await fs.appendFile(path.join(dir,'text.txt'),'changed');await assert.rejects(handleWorkspaceMethod('workspace/prepareTransfer',{cwd:dir,path:'text.txt',mode:'download',revision:info.revision}),e=>e.errorCode==='file_changed');
});
