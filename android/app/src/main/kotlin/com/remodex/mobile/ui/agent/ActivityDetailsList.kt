package com.remodex.mobile.ui.agent

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.ui.turn.LocalActivityDetail
import com.remodex.mobile.ui.turn.TimelineDisclosureButton
import com.remodex.mobile.ui.turn.prepareTimelineItemMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

private const val PageSize = 12

@Composable
internal fun ActivityDetailsList(records: List<TimelineListItem>, render: @Composable (TimelineListItem) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val rows = remember(records, expanded) {
        if (expanded.isEmpty()) records else buildList {
            for (record in records) {
                add(record)
                if (record.stableKey in expanded) (record as? TimelineListItem.FileChangeGroup)?.messages?.forEach { add(TimelineListItem.Single(it)) }
            }
        }
    }
    val scroll = rememberLazyListState()
    LaunchedEffect(rows, scroll) {
        snapshotFlow { scroll.firstVisibleItemIndex / PageSize }.collectLatest { page ->
            withContext(Dispatchers.Default) {
                val start = page * PageSize
                for (i in start until minOf(rows.size, start + 3 * PageSize)) {
                    prepareTimelineItemMarkdown(rows[i], activityDetail = true)
                }
            }
        }
    }
    CompositionLocalProvider(LocalActivityDetail provides true) {
        LazyColumn(state = scroll, modifier = (if (rows.size >= PageSize) Modifier.fillMaxHeight() else Modifier).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(rows, key = { it.stableKey }, contentType = { it.contentType() }) { row ->
                val tools = (row as? TimelineListItem.FileChangeGroup)?.messages
                if (tools == null) {
                    render(row)
                } else {
                    val open = row.stableKey in expanded
                    val title = R.string.turn_timeline_group_modified_files
                    val action = if (open) R.string.turn_timeline_group_toggle_collapse_cd else R.string.turn_timeline_group_toggle_expand_cd
                    TimelineDisclosureButton(stringResource(title), open, {
                        expanded = if (open) expanded - row.stableKey else expanded + row.stableKey
                    }, stringResource(action, tools.size))
                }
            }
        }
    }
}
