package com.r34v.r34v.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R

/** Content-level tabs stay separate from the persistent app navigation. */
@Composable fun ContentTabs(values: List<String>, selected: Int, modifier: Modifier = Modifier, choose: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEachIndexed { index, label ->
            val active = index == selected
            val fill = if (active) RentalPaper else RentalSurface
            Box(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
                .selectable(active, role = Role.Tab) { choose(index) }, contentAlignment = Alignment.Center) {
                Text(label, Modifier.background(fill, RentalShape)
                    .border(1.dp, if (active) fill else RentalEdge, RentalShape).padding(horizontal = 12.dp, vertical = 7.dp),
                    color = if (active) RentalInk else RentalMuted, style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

/** Secondary destinations read as destinations, leaving filled buttons for actions. */
@Composable fun NavigationRow(label: String, icon: Int, modifier: Modifier = Modifier, click: () -> Unit) {
    val panel = Modifier.clip(RentalShape).background(RentalSurface).border(RentalBorder, RentalShape)
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).then(panel).clickable(role = Role.Button, onClick = click)
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Glyph(icon, null)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        CompositionLocalProviderMuted { Glyph(R.drawable.ic_chevron_right, null, Modifier.size(16.dp)) }
    }
}

@Composable private fun CompositionLocalProviderMuted(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant, content = content)
}
