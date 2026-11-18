package com.remodex.mobile.services

import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class WorkspaceImageServiceTest {
    @Test fun projectPathIsSentAndDifferentWorkspacesDoNotReuseRelativeImageMetadata() = runTest {
        val captured = mutableListOf<Map<String, JSONValue>>()
        val service = WorkspaceImageService(MinimalSendRepository { _, params ->
            val p = params!!.objectValue!!
            captured.add(p)
            RPCMessage.success(JSONValue.Str("image"), JSONValue.Obj(mapOf(
                "path" to JSONValue.Str(p["cwd"]!!.stringValue + "/screen.png"),
                "mimeType" to JSONValue.Str("image/png"),
                "dataBase64" to JSONValue.Str("QUJD"),
            )))
        })
        service.readPreviewDataUrl("screen.png", 512, "/project/watch")
        service.readPreviewDataUrl("screen.png", 512, "/project/phone")
        assertEquals(listOf("/project/watch", "/project/phone"), captured.map { it["cwd"]?.stringValue })
        assertNull(captured[1]["ifByteLength"])
    }

    @Test fun imageFailuresKeepOriginalCauseAndCancellation() = runTest {
        val failure = java.io.IOException("image_not_found", IllegalStateException("original cause"))
        failure.addSuppressed(IllegalArgumentException("secondary failure"))
        val service = WorkspaceImageService(MinimalSendRepository { _, _ -> throw failure })
        val actual = kotlin.test.assertFailsWith<java.io.IOException> { service.readPreviewDataUrl("/repo/a.png", 512, "/repo") }
        kotlin.test.assertSame(failure, actual)
        assertEquals("original cause", actual.cause?.message)
        assertEquals("secondary failure", actual.suppressed.single().message)
        val cancelled = kotlinx.coroutines.CancellationException("left conversation")
        val cancelling = WorkspaceImageService(MinimalSendRepository { _, _ -> throw cancelled })
        kotlin.test.assertSame(cancelled, kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            cancelling.readPreviewDataUrl("/repo/a.png", 512)
        })
    }

    @Test fun missingImagePayloadIsAnExplicitResponseFailure() = runTest {
        val service = WorkspaceImageService(MinimalSendRepository { _, _ ->
            RPCMessage.success(JSONValue.Str("image"), JSONValue.Obj(mapOf("path" to JSONValue.Str("/repo/a.png"))))
        })
        val error = kotlin.test.assertFailsWith<java.io.IOException> { service.readPreviewDataUrl("/repo/a.png", 512) }
        kotlin.test.assertTrue(error.message!!.contains("stage=image_preview_response"))
    }

    @Test
    fun readPreviewDataUrl_usesConditionalMetadataAfterFirstRead() = runTest {
        val capturedParams = mutableListOf<Map<String, JSONValue>>()
        val service =
            WorkspaceImageService(
                MinimalSendRepository { method, params ->
                    assertEquals("workspace/readImage", method)
                    val objectParams = params?.objectValue.orEmpty()
                    capturedParams.add(objectParams)
                    val notModified = capturedParams.size == 2
                    RPCMessage.success(
                        id = JSONValue.NumLong(capturedParams.size.toLong()),
                        result =
                            JSONValue.Obj(
                                mapOf(
                                    "path" to JSONValue.Str("/repo/out.png"),
                                    "fileName" to JSONValue.Str("out.png"),
                                    "mimeType" to JSONValue.Str("image/png"),
                                    "byteLength" to JSONValue.NumLong(42),
                                    "mtimeMs" to JSONValue.NumDouble(123.5),
                                    "previewMaxPixelDimension" to JSONValue.NumLong(512),
                                    "notModified" to JSONValue.Bool(notModified),
                                ) +
                                    if (notModified) {
                                        emptyMap()
                                    } else {
                                        mapOf("dataBase64" to JSONValue.Str("QUJD"))
                                    },
                            ),
                    )
                },
            )

        val first = service.readPreviewDataUrl("/repo/out.png", 512)
        val second = service.readPreviewDataUrl("/repo/out.png", 512)

        assertEquals("data:image/png;base64,QUJD", first?.dataUrl)
        assertEquals(first, second)
        assertNull(capturedParams.first()["ifByteLength"])
        assertNull(capturedParams.first()["ifMtimeMs"])
        assertEquals(42L, capturedParams[1]["ifByteLength"]?.longValue)
        assertEquals(123.5, capturedParams[1]["ifMtimeMs"]?.doubleValue)
    }

    @Test
    fun readPreviewDataUrl_separatesPreviewDimensionsInCacheKey() = runTest {
        val capturedDimensions = mutableListOf<Long?>()
        val service =
            WorkspaceImageService(
                MinimalSendRepository { _, params ->
                    val objectParams = params?.objectValue.orEmpty()
                    capturedDimensions.add(objectParams["maxPixelDimension"]?.longValue)
                    RPCMessage.success(
                        id = JSONValue.NumLong(capturedDimensions.size.toLong()),
                        result =
                            JSONValue.Obj(
                                mapOf(
                                    "path" to JSONValue.Str("/repo/out.png"),
                                    "mimeType" to JSONValue.Str("image/png"),
                                    "byteLength" to JSONValue.NumLong(42),
                                    "mtimeMs" to JSONValue.NumDouble(123.5),
                                    "previewMaxPixelDimension" to JSONValue.NumLong(objectParams["maxPixelDimension"]?.longValue ?: 0),
                                    "dataBase64" to JSONValue.Str("QUJD"),
                                ),
                            ),
                    )
                },
            )

        val thumbnail = service.readPreviewDataUrl("/repo/out.png", 512)
        val full = service.readPreviewDataUrl("/repo/out.png", 1600)

        assertEquals(512, thumbnail?.previewMaxPixelDimension)
        assertEquals(1600, full?.previewMaxPixelDimension)
        assertEquals(listOf<Long?>(512L, 1600L), capturedDimensions)
    }
}
