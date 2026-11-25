const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const {createQueueReceipts} = require('../src/queue-receipts');
function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'queue-receipts-'));
  t.after(() => fs.rmSync(dir, {recursive:true,force:true}));
  return {dir, params:{threadId:'thread',clientUserMessageId:randomUUID(),input:[{type:'text',text:'private draft'}]}};
}
test('concurrent, post-drain and post-restart replays produce one native submission', async t => {
  const {dir,params}=fixture(t);
  let calls=0;
  const rpc=async method=>{assert.equal(method,'thread/queue/add'); calls++;return {queuedSubmission:{id:'native'}}};
  const q=createQueueReceipts(dir,rpc);
  const [a,b]=await Promise.all([q.add(params),q.add(params)]);
  assert.deepEqual(a,b);
  assert.deepEqual(await createQueueReceipts(dir,rpc).add(params),a);
  await assert.rejects(q.add({...params,input:[{type:'text',text:'different'}]}),e=>e.data.errorCode==='QUEUE_CONFLICT');
  assert.equal(calls,1);
  assert.ok(!fs.readFileSync(path.join(dir,fs.readdirSync(dir)[0]),'utf8').includes('private draft'));
});
test('after an ambiguous add, a queue lookup can recover its receipt, never re-add',async t=>{
  const {dir,params}=fixture(t);
  await assert.rejects(createQueueReceipts(dir,async()=>{throw new Error('lost response')}).add(params),/lost response/);
  const lookup=rows=>createQueueReceipts(dir,async method=>{assert.equal(method,'thread/queue/list');return {data:rows}});
  await assert.rejects(lookup([]).add(params),e=>e.data.errorCode==='QUEUE_UNCONFIRMED');
  assert.equal((await lookup([{id:'saved',clientUserMessageId:params.clientUserMessageId}]).add(params)).queuedSubmissionId,'saved');
});
test('cancelling before delayed submission prevents dispatch; explicit rejection retains upstream diagnostics',async t=>{
  const {dir,params}=fixture(t);
  const err=Object.assign(new Error('feature unavailable'),{code:-32601,data:{stage:'native'}});
  let calls=0;
  const q=createQueueReceipts(dir,async()=>{calls++;throw err});
  await assert.rejects(q.add(params),e=>e===err && e.notSubmitted && e.data.stage==='native');
  assert.deepEqual(await q.cancelPending(params),{deleted:true});
  await assert.rejects(q.add(params),e=>e.data.errorCode==='QUEUE_CANCELLED');
  assert.equal(calls,1);
});
