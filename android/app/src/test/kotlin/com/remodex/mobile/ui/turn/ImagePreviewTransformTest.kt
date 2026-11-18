package com.remodex.mobile.ui.turn

import kotlin.test.Test
import kotlin.test.assertEquals

class ImagePreviewTransformTest {
    @Test fun zoomAnchorsAtFingerInsteadOfImageCenter() {
        val result = ImagePreviewTransform().transform(400f, 400f, 800f, 800f, 100f, 150f, 2f, 0f, 0f)
        assertEquals(ImagePreviewTransform(2f, 100f, 50f), result)
    }

    @Test fun panUsesFittedImageBoundsIncludingLetterboxing() {
        val wide = ImagePreviewTransform().transform(400f, 800f, 800f, 200f, 200f, 400f, 3f, 9999f, 9999f)
        assertEquals(ImagePreviewTransform(3f, 400f, 0f), wide)
        val tall = ImagePreviewTransform().transform(800f, 400f, 200f, 800f, 400f, 200f, 3f, -9999f, -9999f)
        assertEquals(ImagePreviewTransform(3f, 0f, -400f), tall)
    }

    @Test fun pinchOutClampsAtEightAndPinchBackResetsOffset() {
        val zoomed = ImagePreviewTransform().transform(400f, 400f, 400f, 400f, 200f, 200f, 100f, 100f, 100f)
        assertEquals(8f, zoomed.scale)
        assertEquals(ImagePreviewTransform(), zoomed.transform(400f, 400f, 400f, 400f, 10f, 10f, 0.01f, 50f, 50f))
    }

    @Test fun invalidZoomOrViewportDoesNotCorruptExistingTransform() {
        val current = ImagePreviewTransform(2f, 10f, 20f)
        assertEquals(current, current.transform(0f, 100f, 100f, 100f, 10f, 10f, 2f, 0f, 0f))
        for (zoom in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            assertEquals(current, current.transform(100f, 100f, 100f, 100f, 10f, 10f, zoom, 0f, 0f))
        }
    }

    @Test fun unspecifiedPainterSizeUsesViewportWithoutNaNOffsets() {
        assertEquals(ImagePreviewTransform(2f, 200f, 100f), ImagePreviewTransform().transform(
            400f, 200f, Float.NaN, Float.NaN, 200f, 100f, 2f, 999f, 999f,
        ))
    }
}
