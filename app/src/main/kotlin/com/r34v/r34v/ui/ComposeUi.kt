@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.r34v.r34v.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r34v.r34v.R
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

val CanvasColor = Color.Black
val SurfaceColor = Color(0xff141414)
val RaisedColor = Color(0xff181818)
val MutedColor = Color(0xffb3b3b3)
val BrandColor = Color(0xffe50914)
val MatchColor = Color(0xff46d369)

@Composable fun R34Theme(content: @Composable () -> Unit) {
    val base = Typography()
    val typography = base.copy(
        headlineLarge = base.headlineLarge.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = base.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
    )
    MaterialTheme(typography = typography, colorScheme = darkColorScheme(primary = BrandColor, onPrimary = Color.White,
        secondary = Color.White, background = CanvasColor, surface = SurfaceColor,
        surfaceVariant = RaisedColor, onSurface = Color.White, onSurfaceVariant = MutedColor,
        onBackground = Color.White, secondaryContainer = RaisedColor, onSecondaryContainer = Color.White,
        primaryContainer = RaisedColor, onPrimaryContainer = Color.White, surfaceTint = Color.Transparent,
        surfaceContainer = SurfaceColor, surfaceContainerLow = SurfaceColor, surfaceContainerHigh = RaisedColor,
        surfaceContainerHighest = Color(0xff2a2a2a), surfaceContainerLowest = CanvasColor,
        outline = Color(0xff747474), error = Color(0xffff6670))) {
        Surface(Modifier.fillMaxSize(), color = CanvasColor, contentColor = Color.White, content = content)
    }
}
@Composable fun Title(text: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Text(text, modifier.semantics { heading() }, fontSize = (if (large) 22 else 18).sp, lineHeight = (if (large) 28 else 24).sp, fontWeight = FontWeight.Bold)
}
@Composable fun Copy(text: String, modifier: Modifier = Modifier) { Text(text, modifier, color = MutedColor, fontSize = 13.sp, lineHeight = 19.sp) }
@Composable fun Glyph(@DrawableRes icon: Int, description: String?, modifier: Modifier = Modifier) {
    Icon(painterResource(icon), description, modifier.size(22.dp))
}
@Composable fun IconAction(@DrawableRes icon: Int, description: String, modifier: Modifier = Modifier, click: () -> Unit) {
    IconButton(onClick = click, modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Glyph(icon, description) }
}
@Composable fun TextAction(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = MutedColor.copy(alpha = .5f)), content = content)
}
@Composable fun Action(text: String, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, click: () -> Unit) {
    if (primary) Button(click, modifier.heightIn(min = 48.dp), enabled, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) { Text(text) }
    else FilledTonalButton(click, modifier.heightIn(min = 48.dp), enabled, shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = .12f), contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) { Text(text) }
}
@Composable fun Choice(text: String, selected: Boolean = false, click: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        FilterChip(selected, click, label = { Text(text) }, shape = RoundedCornerShape(20.dp),
            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = Color.White.copy(alpha = .28f), selectedBorderColor = Color.Transparent),
            colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, labelColor = MutedColor, selectedContainerColor = Color.White, selectedLabelColor = Color.Black))
    }
}
@Composable fun Selector(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, click: () -> Unit) {
    FilledTonalButton(click, modifier.heightIn(min = 48.dp), enabled, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = RaisedColor, contentColor = Color.White)) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.width(8.dp)); Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(16.dp))
    }
}
@Composable fun Choices(values: List<String>, selected: Int, choose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEachIndexed { i, text -> Choice(text, i == selected) { choose(i) } }
    }
}
@Composable fun Loading(title: String = "正在加载内容", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = BrandColor, modifier = Modifier.size(28.dp)); Copy(title)
    }
}
@Composable fun EmptyState(title: String, body: String, action: String = "重试", click: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title(title); Copy(body); Action(action, click = click)
    }
}
@Composable fun Expandable(title: String, expandedInitially: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(expandedInitially) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Title(title, Modifier.weight(1f)); Glyph(R.drawable.ic_chevron_down, if (expanded) "收起$title" else "展开$title")
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable fun SiteImage(url: String, site: SiteContext, modifier: Modifier, fit: Boolean = false, ratio: ((Float) -> Unit)? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as R34Application
    val onRatio by rememberUpdatedState(ratio)
    var ready by remember(url, site.id) { mutableStateOf(false) }
    val request = remember(context, url, site.id) {
        val allowed = url.startsWith("https://") || com.r34v.r34v.BuildConfig.DEBUG && url.startsWith("http://")
        ImageRequest.Builder(context).data(url.takeIf { allowed }).memoryCacheKey("${site.id}|$url").diskCacheKey("${site.id}|$url").build()
    }
    Box(modifier.background(Brush.verticalGradient(listOf(Color(0xff242424), SurfaceColor)))) {
        AsyncImage(request, contentDescription = null, imageLoader = app.coverLoader(site),
            modifier = Modifier.fillMaxSize().then(if (ready) Modifier.testTag("cover-ready") else Modifier),
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            error = ColorPainter(SurfaceColor), onSuccess = { result ->
                ready = true
                val image = result.result.image
                if (image.width > 0 && image.height > 0) onRatio?.invoke(image.width.toFloat() / image.height)
            })
    }
}
@Composable fun MediaCard(video: Video, modifier: Modifier = Modifier, menu: List<Pair<String, () -> Unit>> = emptyList(), progress: Float? = null, click: () -> Unit) {
    val site = SiteRegistry.byIdOrDefault(video.site)
    val gallery = site.mediaKind == MediaKind.GALLERY || video.mediaKind == MediaKind.GALLERY
    var ratio by remember(video.site, video.id) { mutableFloatStateOf(if (gallery) video.thumbAspectRatio else 16f / 9f) }
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.testTag("media-${video.site}-${video.id}").combinedClickable(onClick = click, onLongClick = { if (menu.isNotEmpty()) expanded = true }).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.clip(RoundedCornerShape(6.dp))) {
            SiteImage(video.thumb, site, Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(.45f, 1.8f)), gallery) { if (gallery && !video.hasThumbDimensions) ratio = it }
            if (menu.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd)) {
                IconAction(R.drawable.ic_more, "${video.title} 的更多操作", Modifier.background(Color.Black.copy(alpha = .65f), androidx.compose.foundation.shape.CircleShape)) { expanded = true }
                DropdownMenu(expanded, { expanded = false }) { menu.forEach { (label, action) -> DropdownMenuItem({ Text(label) }, { expanded = false; action() }) } }
            }
        }
        Text(video.title, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val metadata = listOf(video.duration, video.views, video.rating).filter(String::isNotBlank).joinToString(" · ")
        if (metadata.isNotBlank()) Text(metadata, color = MutedColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = BrandColor)
    }
}

/** A single Compose dialog host shared by screens and update/download workflows. */
class ComposeDialogs {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
    fun dismiss() { content = null }
    @Composable fun Host() { content?.invoke() }
    fun message(title: String, body: String) { content = { AlertDialog(::dismiss, title = { Text(title) }, text = { Text(body, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextAction(::dismiss) { Text("关闭") } }) } }
    fun confirm(title: String, positive: String = "确认", body: String = "", action: () -> Unit) { content = {
        AlertDialog(::dismiss, title = { Text(title) }, text = if (body.isNotBlank()) ({ Text(body, Modifier.verticalScroll(rememberScrollState())) }) else null,
            confirmButton = { TextAction({ dismiss(); action() }) { Text(positive) } }, dismissButton = { TextAction(::dismiss) { Text("取消") } })
    } }
    fun choose(title: String, values: List<String>, selected: Int = -1, action: (Int) -> Unit) { content = {
        AlertDialog(::dismiss, title = { Text(title) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            values.forEachIndexed { i, value -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { dismiss(); action(i) }, verticalAlignment = Alignment.CenterVertically) {
                if (selected >= 0) RadioButton(selected == i, null); Text(value, Modifier.padding(8.dp))
            } }
        } }, confirmButton = { TextAction(::dismiss) { Text("取消") } })
    } }
    fun edit(title: String, initial: String = "", hint: String = "", keyboard: KeyboardType = KeyboardType.Text, validate: (String) -> String? = { null }, action: (String) -> Unit) { content = {
        var value by remember { mutableStateOf(initial) }; var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(::dismiss, title = { Text(title) }, text = { Column {
            OutlinedTextField(value, { value = it; error = null }, label = { Text(hint.ifBlank { title }) }, isError = error != null, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextAction({ error = validate(value.trim()); if (error == null) { dismiss(); action(value.trim()) } }) { Text("确定") } }, dismissButton = { TextAction(::dismiss) { Text("取消") } })
    } }
}
