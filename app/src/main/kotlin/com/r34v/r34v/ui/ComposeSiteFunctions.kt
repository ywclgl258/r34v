package com.r34v.r34v.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R
import com.r34v.r34v.data.FeedKind
import com.r34v.r34v.data.GalleryLanguage
import com.r34v.r34v.data.ReaderMode
import com.r34v.r34v.data.SiteCategory
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.adapter.Section

@Composable internal fun SiteFunctionsScreen(
    site: SiteContext, page: SiteFunctionPage, language: GalleryLanguage, readerMode: ReaderMode?, preferOriginals: Boolean,
    accountStatus: String?, accountRequired: Boolean, dialogs: ComposeDialogs,
    openSection: (Section) -> Unit, search: (SiteSearchOption, String) -> Unit,
    openFavorites: () -> Unit, openDownloads: () -> Unit, openSubscriptions: () -> Unit,
    openAccount: () -> Unit, setLanguage: (GalleryLanguage) -> Unit, setReaderMode: (ReaderMode) -> Unit, setOriginals: (Boolean) -> Unit,
) {
    var query by rememberSaveable(site.id) { mutableStateOf("") }
    var scopeIndex by rememberSaveable(site.id) { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val accent = MaterialTheme.colorScheme.primary
    val padding = LocalUiDensity.current.pagePadding
    val scope = page.search.getOrNull(scopeIndex) ?: page.search.firstOrNull()
    fun submit() {
        val keyword = query.trim()
        if (keyword.isEmpty() || scope == null) return
        focus.clearFocus()
        search(scope, keyword)
    }

    LazyColumn(Modifier.fillMaxSize().testTag("site-functions-${site.id}"),
        contentPadding = PaddingValues(start = padding, end = padding, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (accountRequired) item("account-required") {
            NavigationRow("登录后浏览本站", R.drawable.ic_person, Modifier.testTag("site-account-required"), openAccount)
        }
        if (!accountRequired && page.featured.isNotEmpty()) item("featured") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                page.featured.forEachIndexed { index, section ->
                    val primary = index == 0
                    val colors = MaterialTheme.colorScheme
                    val ink = if (primary) colors.onPrimary else colors.onSurface
                    Surface(onClick = { openSection(section) }, modifier = Modifier.weight(1f).testTag("site-section-${section.key}"),
                        shape = ArtworkShape, color = if (primary) accent else colors.surface, contentColor = ink) {
                        Column(Modifier.heightIn(min = 86.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Glyph(sectionIcon(section), null, Modifier.size(22.dp))
                            Text(section.title, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        if (scope != null && !accountRequired) item("search") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(query, { query = it }, Modifier.weight(1f).testTag("site-function-search"),
                        singleLine = true, shape = ArtworkShape, colors = filledFieldColors(),
                        placeholder = { Text("搜索${scope.title}") },
                        leadingIcon = { Glyph(R.drawable.ic_search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconAction(R.drawable.ic_close, "清空本站搜索") { query = "" } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }))
                    if (query.isNotBlank()) TextAction(::submit) { Text("搜索") }
                }
                if (page.search.size > 1) {
                    ContentTabs(page.search.map(SiteSearchOption::title), page.search.indexOf(scope)) { scopeIndex = it }
                }
            }
        }
        val note = when (site) {
            SiteRegistry.KBJFREE -> "本站提供的播放和离线片源为预览。"
            SiteRegistry.JMCOMIC -> "打开漫画详情后，可从「全部章节」切换分卷。"
            SiteRegistry.COSPLAYTELE -> "可播放的内嵌视频会在对应图集详情提供入口。"
            else -> ""
        }
        if (note.isNotEmpty()) item("site-usage") { Copy(note) }
        if (readerMode != null || page.languages || page.originals) item("reading-options") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (page.languages) StationFunctionRow("漫画语言", R.drawable.ic_bookmark, language.title, "site-language") {
                    dialogs.choose("本站漫画语言", GalleryLanguage.entries.map { it.title }, language.ordinal) { setLanguage(GalleryLanguage.entries[it]) }
                }
                if (readerMode != null) {
                    val title = when {
                        site.category == SiteCategory.PHOTO -> "写真浏览方式"
                        site == SiteRegistry.IWARA -> "图库浏览方式"
                        else -> "漫画阅读方式"
                    }
                    StationFunctionRow(title, R.drawable.ic_explore, readerMode.title, "site-reader-mode") {
                        dialogs.choose(title, ReaderMode.entries.map { it.title }, readerMode.ordinal) { setReaderMode(ReaderMode.entries[it]) }
                    }
                }
                if (page.originals) StationFunctionRow("原图优先", R.drawable.ic_explore, if (preferOriginals) "已开启" else "已关闭", "site-originals") {
                    dialogs.choose("本站图页画质", listOf("使用站点显示图", "优先原图（消耗更多流量）"), if (preferOriginals) 1 else 0) { setOriginals(it == 1) }
                }
            }
        }
        item("station-library") {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Title("本站片库")
                StationFunctionRow("本站收藏", R.drawable.ic_bookmark, testTag = "site-favorites", click = openFavorites)
                StationFunctionRow("本站下载", R.drawable.ic_download, testTag = "site-downloads", click = openDownloads)
                if (page.subscriptions) StationFunctionRow("本站标签订阅", R.drawable.ic_updates, testTag = "site-subscriptions", click = openSubscriptions)
                if (page.account && !accountRequired) {
                    StationFunctionRow("站点账户", R.drawable.ic_person, accountStatus.orEmpty(), "site-account", openAccount)
                }
            }
        }
        if (!accountRequired && page.sections.isNotEmpty()) {
            item("sections-label") { Title("站内栏目") }
            items(page.sections, key = { "section:${it.key}" }) { section ->
                StationFunctionRow(section.title, sectionIcon(section), section.subtitle, "site-section-${section.key}") { openSection(section) }
            }
        }
    }
}

@Composable private fun StationFunctionRow(
    title: String, icon: Int, subtitle: String = "", testTag: String, click: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(testTag).clip(ArtworkShape)
            .clickable(role = Role.Button, onClick = click).padding(horizontal = 5.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Glyph(icon, null, Modifier.size(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle.isNotBlank()) Copy(subtitle)
            }
            Glyph(R.drawable.ic_chevron_right, null, Modifier.size(14.dp))
        }
        HorizontalDivider(color = colors.outline.copy(alpha = .45f))
    }
}

private fun sectionIcon(section: Section): Int = when (section.feed?.kind) {
    FeedKind.LATEST -> R.drawable.ic_history
    FeedKind.SUBSCRIPTIONS -> R.drawable.ic_updates
    FeedKind.TAG, FeedKind.MODEL, FeedKind.CHARACTER -> R.drawable.ic_bookmark
    FeedKind.SEARCH -> R.drawable.ic_search
    else -> R.drawable.ic_explore
}
