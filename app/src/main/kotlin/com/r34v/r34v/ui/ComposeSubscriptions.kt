package com.r34v.r34v.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r34v.r34v.R
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private enum class SubscriptionView(val title: String) {
    MANAGE("管理标签"), CONTENT("订阅内容"), INBOX("更新收件箱"),
}

private class SubscriptionRefresh {
    var feeds by mutableStateOf<Map<String, TagFeed>>(emptyMap())
    var batch by mutableStateOf<Job?>(null)
    var checkingTag by mutableStateOf<SubscriptionTagCheck?>(null)
    var tagResults by mutableStateOf<Map<String, String>>(emptyMap())
    var error by mutableStateOf<String?>(null)
    var request by mutableIntStateOf(0)
    var completedRequest = -1
    val busy get() = batch != null || checkingTag != null
}

private data class SubscriptionTagCheck(val slug: String, val job: Job)

@Composable fun SubscriptionScreen(app: R34Application, site: SiteContext, dialogs: ComposeDialogs,
    play: (Video) -> Unit, favorite: (Video) -> Unit, saved: Set<String>, openTag: (Subscription) -> Unit,
    revision: () -> Unit, lockedSite: String? = null) {
    val liveTagSources = remember(app, lockedSite) {
        SiteRegistry.all.filter { candidate ->
            (lockedSite == null || candidate.id == lockedSite) &&
                Capability.TAG in AdapterRegistry.adapter(candidate, app.network).capabilities
        }
    }
    val lockedOwner = lockedSite?.let { liveTagSources.singleOrNull() }
    if (lockedSite != null && lockedOwner == null) {
        val name = SiteRegistry.byId(lockedSite)?.name ?: SiteRegistry.retiredVideoHosts[lockedSite] ?: lockedSite
        SubscriptionList {
            item("unsupported") { LibraryNotice("此站点不支持标签订阅", name) }
        }
        return
    }

    val store = app.library
    val namesBySource = remember(lockedOwner) {
        if (lockedOwner != null) mapOf(lockedOwner.id to lockedOwner.name)
        else SiteRegistry.librarySources.associate { it.id to it.name } + SiteRegistry.retiredVideoHosts
    }
    var tagsBySource by remember(store, namesBySource) { mutableStateOf(namesBySource.keys.associateWith(store::subscriptions)) }
    var viewIndex by rememberSaveable(site.id, lockedSite) { mutableIntStateOf(SubscriptionView.MANAGE.ordinal) }
    var manageSource by rememberSaveable(site.id, lockedSite) { mutableStateOf("") }
    var contentSource by rememberSaveable(site.id, lockedSite) { mutableStateOf("") }
    var query by rememberSaveable(site.id, lockedSite) { mutableStateOf("") }
    var unreadOnly by rememberSaveable(site.id, lockedSite) { mutableStateOf(true) }
    val view = SubscriptionView.entries[viewIndex]
    val owner = lockedOwner ?: liveTagSources.firstOrNull { it.id == contentSource }
        ?: liveTagSources.firstOrNull { it.id == site.id }
        ?: liveTagSources.firstOrNull { tagsBySource[it.id].orEmpty().isNotEmpty() }
        ?: SiteRegistry.R34V
    val tags = tagsBySource[owner.id].orEmpty()
    var order by remember(owner.id) { mutableStateOf(store.subscriptionOrder(owner.id)) }
    val refreshBySource = remember(app, site.id, lockedSite) { mutableMapOf<String, SubscriptionRefresh>() }
    val refresh = refreshBySource.getOrPut(owner.id) { SubscriptionRefresh() }
    val scope = rememberCoroutineScope()
    val request = refresh.request
    val checkingTag = refresh.checkingTag
    // Opening the inbox after a row check reads the results already recorded for this source.
    val shouldRefresh = view != SubscriptionView.MANAGE && (
        view == SubscriptionView.CONTENT || request != 0 || refresh.batch != null || refresh.tagResults.isEmpty())

    LaunchedEffect(refresh, shouldRefresh, request, checkingTag, tags) {
        if (!shouldRefresh || tags.isEmpty()) return@LaunchedEffect
        refresh.batch?.join()
        if (refresh.checkingTag != null || refresh.completedRequest == request) return@LaunchedEffect
        val targets = if (request == 0) tags.filterNot { it.slug in refresh.tagResults } else tags
        refresh.batch = currentCoroutineContext()[Job]
        refresh.error = null
        try {
            if (targets.isNotEmpty()) {
                val fetched = app.repository.subscriptionFeeds(owner, targets)
                currentCoroutineContext().ensureActive()
                val feeds = if (lockedOwner == null) fetched else fetched.map { feed ->
                    feed.copy(videos = feed.videos.filter { it.site == owner.id })
                }
                val activeFeeds = synchronized(store) {
                    val followed = store.subscriptions(owner.id).associateBy { it.slug }
                    feeds.filter { followed[it.subscription.slug]?.at == it.subscription.at }.also { active ->
                        val live = active.filter { it.error == null && !it.cached }
                        if (live.isNotEmpty()) store.personal.refreshInbox(owner.id, live)
                    }
                }
                refresh.feeds = refresh.feeds + activeFeeds.associateBy { it.subscription.slug }
            }
            refresh.completedRequest = request
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            refresh.error = e.message ?: "订阅内容加载失败"
            refresh.completedRequest = request
        } finally {
            refresh.batch = null
        }
    }

    fun checkTag(source: SiteContext, entry: Subscription, state: SubscriptionRefresh) {
        if (state.busy) return
        contentSource = source.id
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val fetched = app.repository.subscriptionFeeds(source, listOf(entry)).single()
                currentCoroutineContext().ensureActive()
                if (store.subscriptions(source.id).none { it.slug == entry.slug && it.at == entry.at }) return@launch
                val feed = fetched.copy(videos = fetched.videos.filter { it.site == source.id })
                val status = when {
                    feed.cached -> "使用旧快照 · 未更新收件箱"
                    feed.error != null -> "检查失败 · 未更新收件箱"
                    else -> {
                        val result = store.personal.refreshSubscriptionTag(feed) ?: return@launch
                        if (result.baselineCreated) "联网成功 · 已建立基线 · 新增未读 ${result.newUnread} 项"
                        else "联网成功 · 新增未读 ${result.newUnread} 项"
                    }
                }
                val previous = state.feeds[entry.slug]
                if (previous == null || (feed.error == null && (!feed.cached || previous.cached || previous.error != null))) {
                    state.feeds = state.feeds + (entry.slug to feed)
                }
                state.tagResults = state.tagResults + (entry.slug to status)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (entry.slug !in state.feeds) {
                    state.feeds = state.feeds + (entry.slug to TagFeed(entry, emptyList(), "检查失败"))
                }
                state.tagResults = state.tagResults + (entry.slug to "检查失败 · 未更新收件箱")
            } finally {
                state.checkingTag = null
            }
        }
        state.checkingTag = SubscriptionTagCheck(entry.slug, job)
        job.start()
    }

    fun refreshTags(source: String) {
        tagsBySource = tagsBySource + (source to store.subscriptions(source))
        revision()
    }

    fun browseTags(source: String) = openTag(Subscription(slug = "", site = source))

    val density = LocalUiDensity.current
    val pagePadding = density.pagePadding
    val columns = if (density.compact) 4 else 2
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = pagePadding)) {
            Choices(SubscriptionView.entries.map { it.title }, viewIndex) { viewIndex = it }
        }
        if (view == SubscriptionView.MANAGE) {
            val managedSource = lockedOwner?.id ?: manageSource
            val selectedSources = if (managedSource.isEmpty()) namesBySource.keys else setOf(managedSource)
            val search = query.trim()
            val filtered = remember(tagsBySource, selectedSources, search) {
                selectedSources.flatMap { source ->
                    tagsBySource[source].orEmpty().mapIndexedNotNull { index, entry ->
                        if (entry.title.contains(search, ignoreCase = true) || entry.slug.contains(search, ignoreCase = true)) index to entry else null
                    }
                }
            }
            val total = selectedSources.sumOf { tagsBySource[it].orEmpty().size }
            val browseSource = managedSource.ifEmpty { liveTagSources.firstOrNull { it.id == site.id }?.id ?: owner.id }
            val canBrowse = liveTagSources.any { it.id == browseSource }
            Column(Modifier.padding(horizontal = pagePadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SubscriptionSearch(query) { query = it }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lockedOwner != null) {
                        Copy(lockedOwner.name, Modifier.weight(1f, fill = false))
                    } else {
                        Selector(namesBySource[manageSource] ?: "全部来源", Modifier.weight(1f, fill = false)) {
                            val managedSources = namesBySource.keys.filter { id ->
                                tagsBySource[id].orEmpty().isNotEmpty() || liveTagSources.any { it.id == id } || id == manageSource
                            }.sortedBy { tagsBySource[it].orEmpty().isEmpty() }
                            val sourceChoices = listOf("") + managedSources
                            dialogs.choose("筛选订阅来源", sourceChoices.map { id ->
                                if (id.isEmpty()) "全部来源 · ${tagsBySource.values.sumOf { it.size }}"
                                else "${namesBySource.getValue(id)} · ${tagsBySource[id].orEmpty().size}"
                            }, sourceChoices.indexOf(manageSource)) { index ->
                                manageSource = sourceChoices[index]
                                if (liveTagSources.any { it.id == manageSource }) contentSource = manageSource
                            }
                        }
                    }
                    if (canBrowse) TextAction({ browseTags(browseSource) }) { Text("浏览标签") }
                }
                Copy(if (search.isEmpty()) "$total 个订阅" else "找到 ${filtered.size} 个标签")
            }
            key(managedSource, search) {
                SubscriptionList {
                    if (filtered.isEmpty()) item("management-empty") {
                        when {
                            total == 0 -> Copy("这个范围还没有订阅。可在标签页点“订阅”。", Modifier.padding(vertical = 12.dp))
                            else -> Copy("没有匹配的标签，试试其他名称或标识。", Modifier.padding(vertical = 12.dp))
                        }
                    }
                    items(filtered, key = { (_, entry) -> entry.key }) { (index, entry) ->
                        val source = entry.site
                        val sourceName = namesBySource.getValue(source)
                        val liveSource = liveTagSources.firstOrNull { it.id == source }
                        val state = liveSource?.let { refreshBySource.getOrPut(source) { SubscriptionRefresh() } }
                        val status = if (state?.checkingTag?.slug == entry.slug) "正在检查此标签…" else state?.tagResults?.get(entry.slug)
                        ManagedSubscriptionRow(entry, sourceName, index, tagsBySource.getValue(source).lastIndex,
                            browse = if (liveSource != null) ({ openTag(entry) }) else null,
                            check = if (liveSource != null && state != null) ({ checkTag(liveSource, entry, state) }) else null,
                            canCheck = state?.busy == false, status = status,
                            move = { amount ->
                                val current = store.subscriptions(source).indexOfFirst { it.slug == entry.slug }
                                if (current >= 0 && store.moveSubscription(current, current + amount, source)) refreshTags(source)
                            }, remove = {
                                dialogs.confirm("取消订阅 ${entry.title}？", "取消订阅", "来源为 $sourceName。") {
                                    state?.let {
                                        it.checkingTag?.takeIf { check -> check.slug == entry.slug }?.job?.cancel()
                                        it.feeds = it.feeds - entry.slug
                                        it.tagResults = it.tagResults - entry.slug
                                    }
                                    store.removeSubscription(entry.slug, source)
                                    refreshTags(source)
                                }
                            })
                    }
                }
            }
        } else {
            val inboxRevision by store.personal.changes.collectAsState()
            val blocked by store.blockedTagChanges.collectAsState()
            val inbox = remember(inboxRevision, blocked, owner.id, lockedOwner) {
                val entries = store.personal.visibleSubscriptionEntries(owner.id)
                if (lockedOwner == null) entries else entries.filter { it.video.site == owner.id }
            }
            val page = remember(refresh.feeds, tags, blocked, order) {
                val feeds = tags.mapNotNull { tag -> refresh.feeds[tag.slug]?.copy(subscription = tag) }
                feeds.takeIf { it.isNotEmpty() }?.let { store.subscriptionPage(it, order) }
            }
            var remoteSaved by remember(owner.id, site.id) { mutableStateOf(if (owner.id == site.id) emptySet() else store.subscriptionSavedKeys(owner.id)) }
            val savedKeys = if (owner.id == site.id) saved else remoteSaved
            val toggleFavorite: (Video) -> Unit = { video ->
                favorite(video)
                if (owner.id != site.id) remoteSaved = store.subscriptionSavedKeys(owner.id)
            }
            Column(Modifier.padding(horizontal = pagePadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Copy(if (view == SubscriptionView.CONTENT) "浏览所选来源中已订阅标签的作品。" else "查看检查发现的新作品。首次检查建立基线。")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lockedOwner != null) {
                        Copy(owner.name, Modifier.weight(1f, fill = false))
                    } else {
                        Selector(owner.name, Modifier.weight(1f, fill = false)) {
                            dialogs.choose("选择订阅来源", liveTagSources.map { it.name }, liveTagSources.indexOf(owner)) {
                                contentSource = liveTagSources[it].id
                            }
                        }
                    }
                    TextAction({ if (!refresh.busy) refresh.request++ }, enabled = tags.isNotEmpty() && !refresh.busy) {
                        Text(if (refresh.busy) "检查中" else if (view == SubscriptionView.INBOX) "检查更新" else "刷新")
                    }
                }
            }
            key(view, owner.id) {
                SubscriptionList {
                    if (refresh.error != null) item("error") { Copy(refresh.error.orEmpty()) }
                    page?.failed?.takeIf { it.isNotEmpty() }?.let { failed ->
                        item("partial-error") {
                            Copy("${failed.size} 个标签暂时取不到内容：${failed.joinToString("、") { it.subscription.title }}")
                        }
                    }
                    when {
                        view == SubscriptionView.INBOX -> subscriptionInbox(inbox, unreadOnly, { unreadOnly = it },
                            { keys -> store.personal.markRead(owner.id, keys) }, columns, play, toggleFavorite)
                        tags.isEmpty() -> item("empty") {
                            EmptyState("还没有订阅标签", "在标签页点“订阅”，即可在这里看到该来源的订阅内容。",
                                "浏览标签") { browseTags(owner.id) }
                        }
                        else -> {
                            item("order") {
                                Selector(order.title) {
                                    dialogs.choose("排序方式", SubscriptionOrder.entries.map { "${it.title} · ${it.hint}" }, order.ordinal) {
                                        order = SubscriptionOrder.entries[it]
                                        store.setSubscriptionOrder(order, owner.id)
                                        revision()
                                    }
                                }
                            }
                            if (refresh.busy && page == null) item("loading") { Loading(title = "正在整理订阅内容") }
                            page?.let { subscriptionWorks(it, columns, savedKeys, play, toggleFavorite, openTag) }
                        }
                    }
                }
            }
        }
    }
}

private fun LibraryStore.subscriptionSavedKeys(site: String): Set<String> = favorites(site)
    .filter { it.kind in listOf("video", "gallery") }.map { "${it.site}:${it.target}" }.toSet()

@Composable private fun SubscriptionList(content: LazyListScope.() -> Unit) {
    val padding = LocalUiDensity.current.pagePadding
    LazyColumn(Modifier.fillMaxSize().testTag("subscriptions"),
        contentPadding = PaddingValues(start = padding, end = padding, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable private fun SubscriptionSearch(query: String, change: (String) -> Unit) {
    val focus = LocalFocusManager.current
    TextField(query, change, Modifier.fillMaxWidth().testTag("subscription-search"),
        label = { Text("搜索标签名称或标识") }, singleLine = true, colors = filledFieldColors(),
        leadingIcon = { Glyph(R.drawable.ic_search, null) },
        trailingIcon = if (query.isNotEmpty()) ({ IconAction(R.drawable.ic_close, "清空标签搜索") { change("") } }) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
}

@Composable private fun ManagedSubscriptionRow(entry: Subscription, sourceName: String, index: Int, lastIndex: Int,
    browse: (() -> Unit)?, check: (() -> Unit)?, canCheck: Boolean, status: String?, move: (Int) -> Unit, remove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().artworkPanel().padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.slug != entry.title) Text(entry.slug, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Copy("$sourceName · 顺序 ${index + 1}")
            if (status != null) Copy(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (browse != null) TextAction(browse) { Text("浏览") }
        Box {
            IconAction(R.drawable.ic_more, "管理 ${entry.title} · $sourceName") { expanded = true }
            DropdownMenu(expanded, { expanded = false }) {
                if (check != null) DropdownMenuItem(text = { Text("检查此标签") }, enabled = canCheck,
                    onClick = { expanded = false; check() })
                DropdownMenuItem(text = { Text("上移") }, enabled = index > 0, onClick = { expanded = false; move(-1) })
                DropdownMenuItem(text = { Text("下移") }, enabled = index < lastIndex, onClick = { expanded = false; move(1) })
                DropdownMenuItem(text = { Text("取消订阅") }, onClick = { expanded = false; remove() })
            }
        }
    }
}

private fun LazyListScope.subscriptionInbox(inbox: List<InboxEntry>, unreadOnly: Boolean, showUnreadOnly: (Boolean) -> Unit,
    markRead: (Set<String>) -> Unit, columns: Int, play: (Video) -> Unit, favorite: (Video) -> Unit) {
    val unread = inbox.filterNot { it.read }
    val entries = if (unreadOnly) unread else inbox
    item("inbox-controls") {
        Choices(listOf("未读 ${unread.size}", "全部更新"), if (unreadOnly) 0 else 1) { showUnreadOnly(it == 0) }
        TextAction({ markRead(unread.map { it.video.entryKey }.toSet()) }, enabled = unread.isNotEmpty()) { Text("全部标记已读") }
        if (entries.isEmpty()) Copy(if (unreadOnly) "暂无未读更新" else "暂无订阅更新")
    }
    items(entries.chunked(columns), key = { "inbox:${it.first().video.entryKey}" }) { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(LocalUiDensity.current.cardGap)) {
            row.forEach { entry ->
                val key = setOf(entry.video.entryKey)
                Column(Modifier.weight(1f)) {
                    Copy(if (entry.read) "已读" else "未读")
                    MediaCard(entry.video, menu = listOf("标记已读" to { markRead(key) },
                        "收藏 / 取消收藏" to { favorite(entry.video) })) {
                        markRead(key)
                        play(entry.video)
                    }
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private fun LazyListScope.subscriptionWorks(page: SubscriptionPage, columns: Int, saved: Set<String>, play: (Video) -> Unit,
    favorite: (Video) -> Unit, openTag: (Subscription) -> Unit) {
    if (page.videos.isEmpty()) item("none") { Copy("已订阅的标签下暂无可显示的作品。") }
    if (page.order == SubscriptionOrder.SUBSCRIBED) {
        SubscriptionMerge.grouped(page.tags).forEach { (entry, videos) ->
            item("group:${entry.slug}") {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    TextAction({ openTag(entry) }) { Text("查看标签") }
                }
            }
            items(videos.chunked(columns), key = { row -> "row:${entry.slug}:${row.joinToString("|") { it.entryKey }}" }) {
                SubscriptionRow(it, columns, saved, play, favorite)
            }
        }
    } else {
        items(page.videos.chunked(columns), key = { row -> "row:${row.joinToString("|") { it.entryKey }}" }) {
            SubscriptionRow(it, columns, saved, play, favorite)
        }
    }
}

/** The one-line control placed on tag pages, so following never needs a second screen. */
@Composable fun SubscribeAction(label: String, subscribed: Boolean, click: () -> Unit) {
    Action(label, icon = if (subscribed) R.drawable.ic_check else R.drawable.ic_add, selected = subscribed, click = click)
}

@Composable private fun SubscriptionRow(row: List<Video>, columns: Int, saved: Set<String>, play: (Video) -> Unit, favorite: (Video) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(LocalUiDensity.current.cardGap)) {
        row.forEach { video ->
            MediaCard(video, Modifier.weight(1f),
                menu = listOf((if ("${video.site}:${video.id}" in saved) "已收藏 / 取消收藏" else "收藏") to { favorite(video) })) { play(video) }
        }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}
