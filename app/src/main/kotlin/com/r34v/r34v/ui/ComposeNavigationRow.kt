package com.r34v.r34v.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R

/** Secondary destinations read as destinations, leaving filled buttons for actions. */
@Composable fun NavigationRow(label: String, icon: Int, modifier: Modifier = Modifier, click: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).contentPanel(10).clickable(role = Role.Button, onClick = click)
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Glyph(icon, null)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        CompositionLocalProviderMuted { Glyph(R.drawable.ic_chevron_right, null, Modifier.size(16.dp)) }
    }
}

@Composable private fun CompositionLocalProviderMuted(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides MutedColor, content = content)
}
