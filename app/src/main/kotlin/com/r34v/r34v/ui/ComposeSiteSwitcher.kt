package com.r34v.r34v.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.SiteRegistry

@Composable internal fun SiteSwitcher(current: SiteContext, pinnedIds: List<String>, togglePinned: (String) -> Unit, open: Boolean,
                                     dismiss: () -> Unit, choose: (SiteContext) -> Unit) {
    val categories = remember { SiteRegistry.all.groupBy { it.category }.toSortedMap() }
    val pinned = remember(pinnedIds) { pinnedIds.mapNotNull(SiteRegistry::byId) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(open) { if (!open) editing = false }
    val cellWidth = 98.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val sheetWidth = (windowSizeDp().width.dp - 20.dp).coerceIn(1.dp, 720.dp)
    ModalDrawerSheet(Modifier.width(sheetWidth), drawerContainerColor = RentalInk, drawerContentColor = RentalPaper) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RentalStripes(Modifier.size(23.dp, 16.dp))
            Spacer(Modifier.width(8.dp))
            Title("切换站点", Modifier.weight(1f), large = true)
            TextAction({ expanded = !expanded; if (!expanded) editing = false },
                Modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" }) {
                Text("常用站点", maxLines = 1)
                Glyph(R.drawable.ic_chevron_down, null, Modifier.size(14.dp).rotate(if (expanded) 180f else 0f))
            }
            if (expanded) TextAction({ editing = !editing }) { Text(if (editing) "完成" else "管理") }
            IconAction(R.drawable.ic_close, "关闭站点列表", click = dismiss)
        }
        HorizontalDivider(color = RentalEdge.copy(alpha = .65f))
        LazyVerticalGrid(GridCells.Adaptive(cellWidth), Modifier.fillMaxSize().selectableGroup()
            .semantics { contentDescription = "站点列表" },
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (expanded) {
                if (editing || pinned.isEmpty()) item(key = "pinned-help", span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (editing) "点选站点以置顶或取消" else "还没有常用站点",
                        Modifier.padding(top = 10.dp), color = RentalMuted, style = MaterialTheme.typography.bodySmall)
                } else items(pinned, key = { "shortcut-${it.id}" }) { site ->
                    SiteTile(site, current.id == site.id, false, Modifier.testTag("pinned-site-${site.id}")) { choose(site) }
                }
            }
            categories.forEach { (category, sites) ->
                item(key = category, span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(category.title, Modifier.semantics { heading() }, color = RentalPaper,
                            style = MaterialTheme.typography.titleMedium)
                        Text(sites.size.toString(), color = RentalMuted, style = MaterialTheme.typography.labelMedium)
                        HorizontalDivider(Modifier.weight(1f), color = RentalEdge.copy(alpha = .5f))
                    }
                }
                items(sites, key = { it.id }) { site ->
                    SiteTile(site, if (editing) site.id in pinnedIds else site.id == current.id, editing,
                        Modifier.testTag("site-entry-${site.id}")) {
                        if (editing) togglePinned(site.id) else choose(site)
                    }
                }
            }
        }
    }
}

@Composable private fun SiteTile(site: SiteContext, selected: Boolean, editing: Boolean, modifier: Modifier, click: () -> Unit) {
    val ink = if (selected) RentalInk else RentalPaper
    val action = if (editing) Modifier.toggleable(selected, role = Role.Checkbox) { click() }
        .semantics { contentDescription = if (selected) "取消置顶 ${site.name}" else "置顶 ${site.name}" }
    else Modifier.selectable(selected, role = Role.RadioButton, onClick = click)
    Column(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RentalShape)
        .background(if (selected) RentalPaper else RentalSurface)
        .border(1.dp, if (selected) RentalPaper else RentalEdge, RentalShape).then(action)
        .padding(horizontal = 7.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(site.name, Modifier.weight(1f), color = ink, style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            if (selected) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(13.dp), tint = ink)
        }
        if (site.accessNote.isNotEmpty()) Text(site.accessNote,
            color = if (selected) RentalInk else RentalMuted, style = MaterialTheme.typography.labelMedium)
    }
}
