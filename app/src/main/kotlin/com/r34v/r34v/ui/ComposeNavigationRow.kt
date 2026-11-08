package com.r34v.r34v.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R

/** Content-level tabs stay separate from the persistent app navigation. */
@Composable fun ContentTabs(values: List<String>, selected: Int, modifier: Modifier = Modifier, choose: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        values.forEachIndexed { index, label ->
            val active = index == selected
            val color by animateColorAsState(if (active) Color.White else MutedColor, tween(150), label = "contentTab")
            Column(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).selectable(active, role = Role.Tab) { choose(index) },
                verticalArrangement = Arrangement.SpaceBetween) {
                Text(label, Modifier.padding(vertical = 12.dp), color = color,
                    style = MaterialTheme.typography.titleSmall, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                Box(Modifier.width(24.dp).height(2.dp).background(if (active) BrandColor else Color.Transparent))
            }
        }
    }
}

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
