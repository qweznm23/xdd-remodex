package com.remodex.mobile.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remodex.mobile.R
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet
import kotlin.math.roundToInt

internal data class ProjectAnchor(val id: String, val name: String, val path: String?, val row: Int)

internal fun projectIndexEntries(groups: List<SidebarThreadGroup>): List<ProjectAnchor> =
    groups.mapIndexedNotNull { row, group ->
        if (group.kind == SidebarThreadGroupKind.Project) ProjectAnchor(group.id, group.label, group.projectPath, row) else null
    }

internal fun projectIndexLabels(entries: List<ProjectAnchor>): List<String> {
    val stems = entries.map { it.name.take(3) }
    val used = mutableSetOf<String>()
    return entries.mapIndexed { i, entry ->
        val parts = entry.name.split('-', '_', ' ').filter(String::isNotEmpty)
        val initials = parts.take(3).joinToString("") { it.take(1) }.uppercase()
        val suffix = entry.name.takeLastWhile(Char::isDigit).takeLast(3)
        val candidates = listOf(stems[i].takeIf { stem -> stems.count { it.equals(stem, true) } == 1 },
            suffix, initials, entry.name.takeLast(3), entry.path?.substringBeforeLast('/')?.substringAfterLast('/')?.take(3))
        candidates.filterNotNull().firstOrNull { it.isNotBlank() && used.add(it.uppercase()) }
            ?: generateSequence(i + 1) { it + 1 }.map(Int::toString).first { used.add(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectIndex(entries: List<ProjectAnchor>, list: LazyListState, colors: SidebarColorPalette, modifier: Modifier, openPicker: () -> Unit) {
    var pressed by remember(entries) { mutableStateOf<ProjectAnchor?>(null) }
    val current by remember(entries, list) {
        derivedStateOf { entries.indexOfLast { it.row <= list.firstVisibleItemIndex }.coerceAtLeast(0) }
    }
    var fingerY by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(modifier.heightIn(max = 48.dp + 44.dp * entries.size.coerceAtMost(7)).fillMaxSize()) {
        val trackHeight = constraints.maxHeight
        Column(Modifier.align(Alignment.CenterEnd).width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(modifier = Modifier.height(48.dp), onClick = openPicker) {
                Icon(Icons.Default.Search, stringResource(R.string.session_all_projects), tint = colors.accent, modifier = Modifier.size(18.dp))
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val count = minOf(entries.size, 7, (maxHeight / 44.dp).toInt().coerceAtLeast(1))
                val indices = List(count) { if (count == 1) 0 else (it.toFloat() * entries.lastIndex / (count-1)).roundToInt() }
                val labels = remember(entries, indices) { projectIndexLabels(indices.map(entries::get)) }
                indices.forEachIndexed { slot, index ->
                    val entry = entries[index]
                    Box(Modifier.offset(y = (maxHeight - 44.dp).coerceAtLeast(0.dp) * (index.toFloat() / entries.lastIndex.coerceAtLeast(1)))
                        .size(44.dp).semantics {
                            contentDescription = entry.name
                            onClick { list.requestScrollToItem(entry.row); true }
                        }, contentAlignment = Alignment.Center) {
                        Text(labels[slot], modifier = Modifier.background(
                            if (index == current) colors.selectedRow else androidx.compose.ui.graphics.Color.Transparent,
                            MaterialTheme.shapes.small).padding(horizontal = 4.dp, vertical = 2.dp),
                            color = if (index == current) colors.primaryText else colors.secondaryText,
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.fillMaxSize().pointerInput(entries, indices) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val half = 22.dp.toPx()
                        val travel = (size.height - 2 * half).coerceAtLeast(1f)
                        fun indexAt(y: Float) = (((y-half) / travel).coerceIn(0f, 1f) * entries.lastIndex).roundToInt()
                        val tapped = indices.firstOrNull { kotlin.math.abs(down.position.y - (half + travel * it / entries.lastIndex)) <= half }
                        fun select(i: Int) {
                            if (pressed != entries[i]) {
                                pressed = entries[i]
                                list.requestScrollToItem(entries[i].row)
                            }
                        }
                        fingerY = down.position.y
                        down.consume()
                        try {
                            select(tapped ?: indexAt(down.position.y))
                            do {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                fingerY = change.position.y
                                if (kotlin.math.abs(change.position.y - down.position.y) > viewConfiguration.touchSlop || tapped == null) select(indexAt(change.position.y))
                                change.consume()
                            } while (change.pressed)
                        } finally { pressed = null }
                    }
                })
            }
        }
        pressed?.let { entry ->
            Box(Modifier.align(Alignment.TopEnd).padding(end = 48.dp)
                .offset { IntOffset(0, (48.dp.toPx() + fingerY - 20.dp.toPx()).roundToInt().coerceIn(0, (trackHeight - 44.dp.roundToPx()).coerceAtLeast(0))) }) {
                Text(entry.name, Modifier.widthIn(max = 212.dp).background(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectIndexPicker(entries: List<ProjectAnchor>, onDismiss: () -> Unit, onPick: (ProjectAnchor) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    RemodexModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.session_all_projects), style = MaterialTheme.typography.titleLarge)
            SidebarSearchField(query, { query = it }, placeholderText = stringResource(R.string.session_all_projects))
            val matches = remember(entries, query) { entries.filter { it.name.contains(query, true) || it.path?.contains(query, true) == true } }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                if (matches.isEmpty()) item { Text(stringResource(R.string.session_no_matching_project), Modifier.padding(16.dp)) }
                items(matches, key = { it.id }) { entry ->
                    ListItem(
                        modifier = Modifier.clickable { onPick(entry) },
                        headlineContent = { Text(entry.name) },
                        supportingContent = entry.path?.let { { Text(it) } },
                    )
                }
            }
        }
    }
}
