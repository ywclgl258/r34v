package com.r34v.r34v.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R
import com.r34v.r34v.data.Favorite
import com.r34v.r34v.data.MediaKind
import com.r34v.r34v.data.PersonalStore
import com.r34v.r34v.data.SiteCategory
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.Video

private enum class FavoriteType(val title: String) {
    ALL("全部"), VIDEO("视频"), COMIC("漫画"), PHOTO("写真"), TAG("标签"), CREATOR("作者"), CHARACTER("角色");

    companion object {
        fun of(favorite: Favorite): FavoriteType = when (favorite.kind) {
            "video" -> VIDEO
            "gallery" -> if (SiteRegistry.byId(favorite.site)?.category == SiteCategory.COMIC) COMIC else PHOTO
            "model" -> CREATOR
            "character" -> CHARACTER
            else -> TAG
        }
    }
}

@Composable internal fun FavoritesScreen(
    favorites: List<Favorite>, personal: PersonalStore, dialogs: ComposeDialogs,
    open: (Favorite, Video?) -> Unit, remove: (Favorite) -> Unit, discover: () -> Unit,
    lockedSite: String? = null,
) {
    var query by rememberSaveable(lockedSite) { mutableStateOf("") }
    var source by rememberSaveable(lockedSite) { mutableStateOf("") }
    var type by rememberSaveable(lockedSite) { mutableStateOf(FavoriteType.ALL) }
    var titleOrder by rememberSaveable(lockedSite) { mutableStateOf(false) }
    var selecting by rememberSaveable(lockedSite) { mutableStateOf(false) }
    var selection by rememberSaveable(lockedSite) { mutableStateOf(emptyList<String>()) }
    val focus = LocalFocusManager.current
    val scopedFavorites = remember(favorites, lockedSite) {
        if (lockedSite == null) favorites else favorites.filter { it.site == lockedSite }
    }
    val selectedSource = if (lockedSite == null) source else ""
    val types = remember(scopedFavorites, type) {
        FavoriteType.entries.filter { candidate -> candidate == FavoriteType.ALL || candidate == type || scopedFavorites.any { FavoriteType.of(it) == candidate } }
    }
    val results = remember(scopedFavorites, selectedSource, type, query, titleOrder) {
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val matching = scopedFavorites.filter { item ->
            (selectedSource.isEmpty() || item.site == selectedSource) && (type == FavoriteType.ALL || FavoriteType.of(item) == type) &&
                terms.all { term -> listOf(item.title, item.target, SiteRegistry.displayName(item.site)).any { it.contains(term, ignoreCase = true) } }
        }
        if (titleOrder) matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        else matching.sortedByDescending { it.at }
    }
    val filtered = selectedSource.isNotEmpty() || type != FavoriteType.ALL || query.isNotBlank()
    fun reset() { query = ""; source = ""; type = FavoriteType.ALL; focus.clearFocus() }
    val selectable = remember(results, lockedSite) {
        if (lockedSite == null) emptyMap() else results.mapNotNull { item ->
            item.media(SiteRegistry.byId(item.site))?.let { item.key to it }
        }.toMap()
    }
    val selectedKeys = remember(selection) { selection.toSet() }
    val selected = remember(selectable, selectedKeys, selecting) {
        if (selecting) selectable.filterKeys { it in selectedKeys }.values.toList()
        else emptyList()
    }
    fun stopSelecting() { selecting = false; selection = emptyList() }
    BackHandler(selecting) { stopSelecting() }
    LaunchedEffect(selectable, selecting) {
        val visible = if (selecting) selection.filter { it in selectable } else emptyList()
        if (visible.size != selection.size) selection = visible
    }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("favorites-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        item("filters") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextField(query, { query = it }, Modifier.fillMaxWidth().testTag("favorites-search"), singleLine = true,
                    colors = filledFieldColors(), shape = ArtworkShape, placeholder = { Text("搜索收藏名称或站点") },
                    leadingIcon = { Glyph(R.drawable.ic_search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconAction(R.drawable.ic_close, "清空收藏搜索") { query = "" } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
                if (types.size > 1) ContentTabs(types.map { it.title }, types.indexOf(type)) { type = types[it]; focus.clearFocus() }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lockedSite == null) {
                        Selector(if (source.isEmpty()) "全部来源" else SiteRegistry.displayName(source), Modifier.weight(1f)) {
                            val sources = (scopedFavorites.map { it.site } + source).distinct().filter(String::isNotBlank).sortedBy(SiteRegistry::displayName)
                            focus.clearFocus()
                            dialogs.choose("收藏来源", listOf("全部来源") + sources.map(SiteRegistry::displayName), sources.indexOf(source) + 1) {
                                source = sources.getOrNull(it - 1).orEmpty()
                            }
                        }
                    } else {
                        Copy(SiteRegistry.displayName(lockedSite), Modifier.weight(1f))
                    }
                    Selector(if (titleOrder) "按名称" else "最近收藏", Modifier.weight(1f)) {
                        focus.clearFocus()
                        dialogs.choose("收藏排序", listOf("最近收藏", "按名称"), if (titleOrder) 1 else 0) { titleOrder = it == 1 }
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Copy("${results.size} 项收藏", Modifier.weight(1f))
                    if (filtered) TextAction(::reset) { Text("清空筛选") }
                    if (selectable.isNotEmpty() && !selecting) TextAction({
                        focus.clearFocus(); selection = selectable.keys.toList(); selecting = true
                    }) { Text("多选") }
                }
            }
        }
        if (results.isEmpty()) item("empty") {
            if (filtered) {
                val hint = if (lockedSite == null) "试试其他名称或来源。" else "试试其他关键词，或清空筛选条件。"
                LibraryNotice("没有符合条件的收藏", hint)
            } else LibraryNotice("还没有收藏内容", "收藏作品、作者或标签后，可在这里找回。") { Action("去发现", click = discover) }
        }
        items(results, key = Favorite::key) { favorite ->
            val chosen = favorite.key in selectedKeys
            FavoriteRow(favorite, personal, dialogs, open, remove,
                selecting = selecting, selection = chosen.takeIf { selecting && favorite.key in selectable },
                toggle = { checked -> selection = if (checked) selectedKeys.toList() + favorite.key else (selectedKeys - favorite.key).toList() })
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .55f))
        }
    }
        if (selecting) Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction({
                    selection = if (selected.size == selectable.size) emptyList() else selectable.keys.toList()
                }) { Text(if (selected.size == selectable.size) "取消全选" else "选择当前结果") }
                Text("${selected.size} 项", Modifier.weight(1f))
                Action("加入片单", primary = true, enabled = selected.isNotEmpty(), click = {
                    val current = selected
                    val destinations = personal.shelves()
                    dialogs.choose("加入片单", destinations.map { it.name }) { index ->
                        val target = destinations[index]
                        val added = personal.addToExistingShelf(current, target.id)
                        stopSelecting()
                        dialogs.message("已加入片单", "新增 $added 项，${current.size - added} 项已在「${target.name}」中。原顺序和完成状态已保留。")
                    }
                })
                IconAction(R.drawable.ic_close, "退出多选", click = ::stopSelecting)
            }
        }
    }
}

@Composable private fun FavoriteRow(favorite: Favorite, personal: PersonalStore, dialogs: ComposeDialogs,
    open: (Favorite, Video?) -> Unit, remove: (Favorite) -> Unit, selecting: Boolean, selection: Boolean?, toggle: (Boolean) -> Unit) {
    val site = SiteRegistry.byId(favorite.site)
    val type = FavoriteType.of(favorite)
    val video = remember(favorite, site) { favorite.media(site) }
    val title = favorite.title.ifBlank { favorite.target }
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).testTag("favorite-${favorite.key}").clip(ArtworkShape)
        .then(when {
            selection != null -> Modifier.toggleable(selection, role = Role.Checkbox, onValueChange = toggle)
            selecting -> Modifier
            else -> Modifier.clickable(role = Role.Button) { open(favorite, video) }
        }).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selection != null) Checkbox(selection, null, modifier = Modifier.size(40.dp))
        if (site != null && favorite.thumb.isNotBlank()) SiteImage(favorite.thumb, site,
            Modifier.size(66.dp, 54.dp).clip(ArtworkShape), fit = type != FavoriteType.VIDEO)
        else Surface(modifier = Modifier.size(66.dp, 54.dp), shape = ArtworkShape, color = MaterialTheme.colorScheme.surface) {
            Box(contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_bookmark, null, Modifier.size(18.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            val label = if (site == null) "来源已移除" else type.title
            Copy(listOf(SiteRegistry.displayName(favorite.site), label, favorite.subtitle).filter(String::isNotBlank).joinToString(" · "))
        }
        if (!selecting) Box {
            IconAction(R.drawable.ic_more, "$title 的更多操作") { expanded = true }
            DropdownMenu(expanded, { expanded = false }) {
                if (video != null) {
                    val waiting = personal.contains(video)
                    DropdownMenuItem({ Text(if (waiting) "移出稍后看" else "加入稍后看") }, {
                        expanded = false
                        if (waiting) personal.remove(video) else personal.add(video)
                    })
                    DropdownMenuItem({ Text("加入合集") }, { expanded = false; dialogs.addToShelf(personal, video) })
                }
                DropdownMenuItem({ Text("取消收藏") }, { expanded = false; remove(favorite) })
            }
        }
    }
}

private fun Favorite.media(site: SiteContext?): Video? {
    if (site == null || target.isBlank() || kind !in listOf("video", "gallery")) return null
    return Video(target, title, site.absolute(path), thumb, subtitle, site = site.id,
        mediaKind = if (kind == "gallery") MediaKind.GALLERY else MediaKind.VIDEO)
}
