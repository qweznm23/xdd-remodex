package com.remodex.mobile.services
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.test.*

class VideoRangeReaderTest {
    private val size=12L*1024*1024
    private val ticket=FileStreamTicket("a".repeat(64),ByteArray(32),"b".repeat(64),size)
    @Test fun readsOnlyNeededBlocksCachesThemAndAllowsBackwardSeeking() {
        val requested=mutableListOf<Long>()
        val reader=VideoRangeReader("http://localhost" to ticket,renew={error("unexpected renewal")},fetch={_,_,start,length->requested+=start;ByteArray(length.toInt()) {((start+it)%251).toByte()}})
        try {
            val buffer=ByteArray(32)
            assertEquals(32,reader.read(1234,buffer,0,32));assertContentEquals(ByteArray(32){((1234+it)%251).toByte()},buffer)
            reader.read(2000,buffer,0,32);assertEquals(listOf(0L),requested)
            reader.read(8*1024*1024L+100,buffer,0,32);reader.read(1234,buffer,0,32)
            assertEquals(listOf(0L,8*1024*1024L),requested)
            for(i in 0..10)reader.read(i*1024*1024L,buffer,0,32)
            val calls=requested.size;reader.read(0,buffer,0,32);assertEquals(calls+1,requested.size)
            assertEquals(-1,reader.read(size,buffer,0,32))
        } finally {reader.close()}
    }
    @Test fun expiryRenewsOnceButNeverMixesChangedFiles() {
        val expired=FileTransferFailure("stream_http","http_401","transfer_expired")
        val reader=VideoRangeReader("http://localhost" to ticket,renew={"http://localhost" to ticket.copy(revision="c".repeat(64))},fetch={_,_,_,_->throw expired})
        try {val failure=assertFailsWith<FileTransferFailure>{reader.read(0,ByteArray(1),0,1)};assertEquals("file_changed",failure.code);assertTrue(failure.suppressed.any{it===expired})}
        finally{reader.close()}
    }
    @Test fun closeCancelsPendingRangeAndPreservesCancellationCause() {
        val entered=CountDownLatch(1);val exited=CountDownLatch(1);var failure:Throwable?=null
        val reader=VideoRangeReader("http://localhost" to ticket,renew={error("unexpected")},fetch={_,_,_,_->entered.countDown();awaitCancellation()})
        val thread=Thread{try{reader.read(0,ByteArray(1),0,1)}catch(e:Throwable){failure=e}finally{exited.countDown()}}
        thread.start();assertTrue(entered.await(3,TimeUnit.SECONDS));reader.close();assertTrue(exited.await(3,TimeUnit.SECONDS))
        assertIs<InterruptedIOException>(failure);assertIs<CancellationException>(failure!!.cause)
    }
}
