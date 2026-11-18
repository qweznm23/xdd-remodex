package com.remodex.mobile.services

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec

@UnstableApi
internal class EncryptedVideoDataSource(private val reader: VideoRangeReader): BaseDataSource(true) {
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (dataSpec.position > reader.byteLength || dataSpec.position < 0) throw FileTransferFailure("video_open","invalid_offset","Video position is outside the file")
        uri = dataSpec.uri
        position = dataSpec.position
        remaining = reader.byteLength-position
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining,dataSpec.length)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = reader.read(position,buffer,offset,minOf(length.toLong(),remaining).toInt())
        if (count < 0) throw FileTransferFailure("video_read","unexpected_eof","Video ended before the requested range")
        position += count; remaining -= count; bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri? = uri
    override fun close() { uri = null; if (opened) {opened = false; transferEnded()} }
}
