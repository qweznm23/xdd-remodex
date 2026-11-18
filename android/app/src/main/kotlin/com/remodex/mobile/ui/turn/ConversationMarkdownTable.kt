package com.remodex.mobile.ui.turn

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.remodex.mobile.R
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR

/** Keep the table viewport bounded; only its content grows horizontally. Cells never elide text. */
@Composable
internal fun ConversationMarkdownTable(model: MarkdownComponentModel) {
    val columns = remember(model.node) { model.node.findChildOfType(HEADER)?.children?.count { it.type == CELL } ?: 0 }
    if (columns == 0) return
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val viewportWidth = maxWidth
        val tableWidth = maxOf(viewportWidth, columns * 200.dp)
        Column {
            if (tableWidth > viewportWidth) {
                Text(stringResource(R.string.markdown_table_scroll_hint), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .background(LocalMarkdownColors.current.tableBackground).horizontalScroll(scroll)) {
                Column(Modifier.width(tableWidth)) {
                    model.node.children.forEach { row ->
                        when (row.type) {
                            HEADER -> MarkdownTableHeader(model.content, row, tableWidth, model.typography.table,
                                verticalAlignment = Alignment.Top, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
                            ROW -> MarkdownTableRow(model.content, row, tableWidth, model.typography.table,
                                verticalAlignment = Alignment.Top, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
                            TABLE_SEPARATOR -> MarkdownDivider()
                        }
                    }
                }
            }
        }
    }
}
