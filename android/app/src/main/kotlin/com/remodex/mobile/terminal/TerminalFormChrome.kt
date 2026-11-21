package com.remodex.mobile.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remodex.mobile.ui.sidebar.rememberSidebarColorPalette
import com.remodex.mobile.ui.theme.isAgentLightChrome

internal val TerminalViewportShape = RoundedCornerShape(12.dp)

@Composable
fun rememberTerminalViewportBackground(): Color {
    val lightChrome = isAgentLightChrome()
    return remember(lightChrome) {
        if (lightChrome) {
            Color(0xFF0C0C0E)
        } else {
            Color(0xFF000000)
        }
    }
}

@Composable
fun TerminalSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = rememberSidebarColorPalette()
    Text(
        text = text,
        modifier = modifier,
        style =
            MaterialTheme.typography.labelLarge.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        color = colors.mutedText,
    )
}

@Composable
fun TerminalTopBarAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    destructive: Boolean = false,
) {
    val contentColor = when {
        destructive -> MaterialTheme.colorScheme.error
        emphasis -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    TextButton(onClick = onClick, modifier = modifier, colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 8.dp)) { Text(text) }
}

@Composable
fun TerminalTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    TerminalTopBarAction(text = text, onClick = onClick, modifier = modifier, destructive = destructive)
}

@Composable
fun TerminalEmptyState(
    text: String,
    modifier: Modifier = Modifier,
) {
    TerminalInfoCard(text = text, modifier = modifier)
}

@Composable
fun TerminalGuideStepCard(
    title: String,
    command: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        TerminalCommandExample(command)
    }
}

@Composable
internal fun TerminalCommandExample(command: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(command, Modifier.fillMaxWidth().padding(12.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    }
}

@Composable
fun TerminalViewportFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    OutlinedCard(modifier = modifier) {
        Box(Modifier.fillMaxSize().padding(3.dp).clip(TerminalViewportShape)) { content() }
    }
}

@Composable
fun TerminalFormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    fieldHeight: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth().then(fieldHeight),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
    )
}

@Composable
fun TerminalFormCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = rememberSidebarColorPalette()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = colors.primaryText,
        )
    }
}

@Composable
fun TerminalPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = if (fullWidth) 48.dp else 40.dp),
    ) { Text(text) }
}

@Composable
fun TerminalSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(text)
    }
}

@Composable
fun TerminalProfileCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

@Composable
fun TerminalInfoCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun TerminalAccessoryKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    if (selected == null) {
        AssistChip(onClick = onClick, label = { Text(label) }, leadingIcon = leadingIcon, modifier = modifier)
    } else {
        FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, leadingIcon = leadingIcon, modifier = modifier)
    }
}
