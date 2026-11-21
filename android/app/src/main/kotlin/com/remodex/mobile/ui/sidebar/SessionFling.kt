// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE-Flutter file.
// Flutter ClampingScrollSimulation 的 Android Compose 适配，来源 MyWidget prism-ui/PrismFling.kt；许可见 assets/licenses/LICENSE-Flutter。
package com.remodex.mobile.ui.sidebar

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** 输入为逻辑像素（dp）/秒；与 Flutter Android 的 clamping 曲线对齐，不调整手指拖动比例。 */
internal class PrismClampingMotion(private val velocity: Double) {
    private val rate = ln(.78) / ln(.9)
    private val referenceVelocity = .015 * 9.80665 * 39.37 * 160 * .84 / .35
    val duration = rate * .35 * (abs(velocity) / referenceVelocity).pow(1 / (rate - 1))
    val distance = velocity * duration / rate
    private fun remaining(seconds: Double) = if (duration == 0.0) 0.0 else (1 - seconds / duration).coerceIn(0.0, 1.0)
    fun position(seconds: Double) = distance * (1 - remaining(seconds).pow(rate))
    fun velocity(seconds: Double) = velocity * remaining(seconds).pow(rate - 1)
}

internal class PrismFlingBehavior(private val density: Float) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) <= 1f) return initialVelocity
        val motion = PrismClampingMotion((initialVelocity / density).toDouble())
        val start = withFrameNanos { it }
        var previous = 0f
        while (true) {
            val elapsed = withFrameNanos { (it - start) / 1_000_000_000.0 }
            val position = (motion.position(elapsed) * density).toFloat()
            val delta = position - previous
            val consumed = scrollBy(delta)
            previous = position
            // 触底时把剩余速度交还嵌套滚动，而不是继续空转或重复积分。
            if (abs(delta - consumed) > .5f) return (motion.velocity(elapsed) * density).toFloat()
            if (elapsed >= motion.duration) return 0f
        }
        // 新触摸由 Scrollable 取消此协程；取消保持传播，不继续驱动旧惯性。
    }
}

@Composable
internal fun rememberSessionFling(): FlingBehavior {
    val density = LocalDensity.current.density
    return remember(density) { PrismFlingBehavior(density) }
}
