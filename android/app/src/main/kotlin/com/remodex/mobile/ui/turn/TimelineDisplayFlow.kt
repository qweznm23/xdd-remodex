package com.remodex.mobile.ui.turn

import androidx.compose.runtime.compositionLocalOf
import com.remodex.mobile.core.model.CodexMessage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample

/** Rendering resumes as soon as the conversation starts becoming visible, before read receipts. */
internal val LocalTimelineRenderingEnabled = compositionLocalOf { true }

/** Only presentation is sampled; repository state still contains every received delta. */
internal fun StateFlow<Map<String, List<CodexMessage>>>.threadDisplayFlow(
    threadId: String,
): Flow<List<CodexMessage>> =
    map { it[threadId].orEmpty() }
        .distinctUntilChanged()
        .sampleTimelineDisplay()
        .onStart { emit(value[threadId].orEmpty()) }

@OptIn(FlowPreview::class)
internal fun <T> Flow<T>.sampleTimelineDisplay(): Flow<T> = sample(50L)

/** Pause hidden presentation work, while the repository continues receiving and retaining every delta. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<T>.whileTimelineVisible(visible: Flow<Boolean>): Flow<T> =
    visible.distinctUntilChanged().flatMapLatest { showing -> if (showing) this else emptyFlow() }
