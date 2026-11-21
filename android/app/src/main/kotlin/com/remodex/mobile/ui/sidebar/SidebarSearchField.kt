package com.remodex.mobile.ui.sidebar

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.R

@Composable
fun SidebarSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SidebarSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholderText = stringResource(R.string.sidebar_search_hint),
        modifier = modifier,
    )
}

@Composable
fun SidebarSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholderText: String? = null,
    modifier: Modifier = Modifier,
    focusOnOpen: Boolean = false,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(focusOnOpen) { if (focusOnOpen) requester.requestFocus() }
    val hint = placeholderText ?: stringResource(R.string.sidebar_search_hint)
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().focusRequester(requester),
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        placeholder = { Text(hint, maxLines = 1) },
        leadingIcon = { Icon(painterResource(LucideR.drawable.lucide_ic_search), null) },
        trailingIcon = if (query.isEmpty()) null else {
            { IconButton(onClick = { onQueryChange("") }) {
                Icon(painterResource(LucideR.drawable.lucide_ic_x), stringResource(R.string.cd_clear_search))
            } }
        },
    )
}
