@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.r34v.r34v.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r34v.r34v.R
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import kotlinx.coroutines.*

/**
 * The subscription screen: only works from tags the user follows on the current site.
 *
 * Two halves that answer different questions. The top explains and orders what is followed; the
 * body is the merged result. Keeping the tag list visible matters because an empty result is
 * usually caused by one tag, and the user has to see which one before they can fix it.
 */
@Composable fun SubscriptionScreen(app: R34Application, site: SiteContext, dialogs: ComposeDialogs,
    play: (Video) -> Unit, favorite: (Video) -> Unit, saved: Set<String>, openTag: (Subscription) -> Unit,
    revision: () -> Unit) {
    val store = app.library
    val scope = rememberCoroutineScope()
    var tags by remember(site.id) { mutableStateOf(store.subscriptions(site.id)) }
    var order by remember(site.id) { mutableStateOf(store.subscriptionOrder(site.id)) }
    var page by remember(site.id) { mutableStateOf<SubscriptionPage?>(null) }
    var busy by remember(site.id) { mutableStateOf(false) }
    var error by remember(site.id) { mutableStateOf<String?>(null) }
    var reload by remember(site.id) { mutableIntStateOf(0) }
    var inboxMode by rememberSaveable(site.id) { mutableIntStateOf(0) }
    var unreadOnly by rememberSaveable(site.id) { mutableStateOf(true) }
    val inboxRevision by store.personal.changes.collectAsState()
    val inbox = remember(inboxRevision, site.id) { store.personal.inbox(site.id) }

    LaunchedEffect(site.id, order, tags.map { it.slug }, reload) {
        if (tags.isEmpty()) { page = null; return@LaunchedEffect }
        busy = true; error = null
        try { page = app.repository.subscriptions(site, order, tags).also { store.personal.refreshInbox(site.id, it.tags) } }
        catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "订阅内容加载失败" }
        finally { busy = false }
    }

    fun refreshTags() { tags = store.subscriptions(site.id); revision() }

    /** Reorder, rename and unfollow without leaving the page. */
    fun manageTags() {
        dialogs.content = {
            val current = remember { mutableStateListOf<Subscription>().apply { addAll(store.subscriptions(site.id)) } }
            AlertDialog({ dialogs.dismiss() }, title = { Text("管理订阅标签") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Copy("顺序即排序：在“订阅顺序”下按这个列表分组显示。")
                    current.forEachIndexed { index, entry ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Copy(entry.slug, Modifier.padding(top = 2.dp))
                            }
                            if (index > 0) IconAction(R.drawable.ic_chevron_down, "上移 ${entry.title}", Modifier.rotate(180f)) {
                                store.moveSubscription(index, index - 1, site.id); current.add(index - 1, current.removeAt(index)); refreshTags()
                            }
                            if (index < current.lastIndex) IconAction(R.drawable.ic_chevron_down, "下移 ${entry.title}") {
                                store.moveSubscription(index, index + 1, site.id); current.add(index + 1, current.removeAt(index)); refreshTags()
                            }
                            IconAction(R.drawable.ic_close, "取消订阅 ${entry.title}") {
                                store.removeSubscription(entry.slug, site.id); current.removeAt(index); refreshTags()
                            }
                        }
                    }
                }
            }, confirmButton = { TextAction({ dialogs.dismiss() }) { Text("完成") } })
        }
    }

    val columns = if (LocalUiDensity.current.compact) 4 else 2
    LazyColumn(Modifier.fillMaxSize().testTag("subscriptions"), contentPadding = PaddingValues(start = LocalUiDensity.current.pagePadding, end = LocalUiDensity.current.pagePadding, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Title("订阅", large = true)
                        Copy(if (tags.isEmpty()) "在标签页点“订阅”，这里就会只显示你关注的内容"
                             else "${site.name} · 关注 ${tags.size} 个标签 · ${page?.videos?.size ?: 0} 部作品")
                    }
                    if (tags.isNotEmpty()) TextAction({ reload++ }, enabled = !busy) { Text(if (busy) "刷新中" else "刷新") }
                }
                if (tags.isNotEmpty()) {
                    Choices(listOf("订阅内容", "更新收件箱"), inboxMode) { inboxMode = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Selector(order.title) { dialogs.choose("排序方式", SubscriptionOrder.entries.map { "${it.title} · ${it.hint}" }, order.ordinal) {
                            order = SubscriptionOrder.entries[it]; store.setSubscriptionOrder(order, site.id); revision()
                        } }
                        Spacer(Modifier.width(8.dp))
                        TextAction(::manageTags) { Text("管理标签") }
                    }
                    if (page != null && page!!.failed.isNotEmpty()) Copy("${page!!.failed.size} 个标签暂时取不到内容：${page!!.failed.joinToString("、") { it.subscription.title }}")
                }
            }
        }
        if (tags.isEmpty()) {
            item("empty") { EmptyState("还没有订阅任何标签",
                "在标签索引或任意标签页点“订阅”，这个页面就只会显示你关注标签下的作品。每个站点的订阅互相独立。",
                "浏览标签") { openTag(Subscription(slug = "", site = site.id)) }
            }
        } else if (inboxMode == 1) {
            val entries = inbox.entries.filter { (!unreadOnly || !it.read) && store.allowed(it.video, site.id) }
            item("inbox-controls") {
                Choices(listOf("未读 ${inbox.entries.count { !it.read }}", "全部更新"), if (unreadOnly) 0 else 1) { unreadOnly = it == 0 }
                TextAction({ store.personal.markRead(site.id, inbox.entries.map { it.video.entryKey }.toSet()) }) { Text("全部标记已读") }
                Copy("首次刷新建立基线，后续刷新标出新出现的作品。")
                if (entries.isEmpty()) Copy("暂时没有${if (unreadOnly) "未读" else ""}更新")
            }
            items(entries.chunked(columns), key = { "inbox:${it.first().video.entryKey}" }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(LocalUiDensity.current.cardGap)) {
                    row.forEach { entry -> Column(Modifier.weight(1f)) {
                        Copy(if (entry.read) "已读" else "未读")
                        MediaCard(entry.video, menu = listOf("标记已读" to { store.personal.markRead(site.id, setOf(entry.video.entryKey)) },
                            "收藏 / 取消收藏" to { favorite(entry.video) })) {
                            store.personal.markRead(site.id, setOf(entry.video.entryKey)); play(entry.video)
                        }
                    } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            if (busy && page == null) item("loading") { Loading(title = "正在整理订阅内容") }
            error?.let { message -> item("error") { EmptyState("订阅加载失败", message) { reload++ } } }
            if (page != null) {
                val data = page!!
                val showGrouped = order == SubscriptionOrder.SUBSCRIBED
                if (data.videos.isEmpty() && !busy) item("none") {
                    Copy("关注的标签下暂时没有符合条件的新作品。可在“管理标签”里调整顺序，或取消不再关心的标签。")
                }
                if (showGrouped) {
                    // Grouped: the tag order is the user's own ranking, so headings carry it.
                    SubscriptionMerge.grouped(data.tags).forEach { (entry, videos) ->
                        item("group:${entry.slug}") {
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                TextAction({ openTag(entry) }) { Text("查看标签") }
                            }
                        }
                        items(videos.chunked(columns), key = { row -> "row:${entry.slug}:${row.joinToString("|") { it.id }}" }) { row -> SubscriptionRow(row, saved, play, favorite) }
                    }
                } else {
                    items(data.videos.chunked(columns), key = { row -> "row:${row.joinToString("|") { "${it.site}:${it.id}" }}" }) { row -> SubscriptionRow(row, saved, play, favorite) }
                }
            }
        }
    }
}

/** The one-line control placed on tag pages, so following never needs a second screen. */
@Composable fun SubscribeAction(label: String, subscribed: Boolean, click: () -> Unit) {
    val compact = LocalUiDensity.current.compact
    FilledTonalButton(click, Modifier.minimumInteractiveComponentSize().heightIn(min = LocalUiDensity.current.controlHeight),
        shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 9.dp else 12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (subscribed) BrandColor else Color.White.copy(alpha = .12f), contentColor = Color.White)) {
        Glyph(if (subscribed) R.drawable.ic_check else R.drawable.ic_add, null, Modifier.size(if (compact) 16.dp else 18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable private fun SubscriptionRow(row: List<Video>, saved: Set<String>, play: (Video) -> Unit, favorite: (Video) -> Unit) {
    val columns = if (LocalUiDensity.current.compact) 4 else 2
    Row(horizontalArrangement = Arrangement.spacedBy(LocalUiDensity.current.cardGap)) {
        row.forEach { video ->
            MediaCard(video, Modifier.weight(1f),
                menu = listOf((if ("${video.site}:${video.id}" in saved) "已收藏 / 取消收藏" else "收藏") to { favorite(video) })) { play(video) }
        }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}
