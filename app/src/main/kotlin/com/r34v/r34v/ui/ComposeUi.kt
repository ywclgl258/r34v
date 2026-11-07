@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.r34v.r34v.ui

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
val RaisedColor = Color(0xff202020)
val ControlColor = Color(0xff2b2b2b)
val ActiveControlColor = Color(0xff404040)
val ControlFocusColor = Color(0xff686868)
val SelectedControlColor = Color(0xfff2f2f2)
val MutedColor = Color(0xffb8b8b8)
val BrandColor = Color(0xffef4954)
val MatchColor = Color(0xff46d369)
val GlassTopColor = Color(0xee202020)
val GlassBottomColor = Color(0xf0121212)

@Composable fun filledFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = ControlColor, unfocusedContainerColor = RaisedColor,
    disabledContainerColor = SurfaceColor, errorContainerColor = Color(0xff301417),
    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent, errorIndicatorColor = MaterialTheme.colorScheme.error,
    cursorColor = Color.White, focusedLabelColor = Color.White, unfocusedLabelColor = MutedColor)

data class UiDensity(val compact: Boolean, val controlHeight: androidx.compose.ui.unit.Dp,
    val contentPadding: androidx.compose.ui.unit.Dp, val sectionGap: androidx.compose.ui.unit.Dp,
    val pagePadding: androidx.compose.ui.unit.Dp, val cardGap: androidx.compose.ui.unit.Dp)
val LocalUiDensity = staticCompositionLocalOf { UiDensity(false, 48.dp, 16.dp, 24.dp, 18.dp, 12.dp) }

@Composable fun R34Theme(content: @Composable () -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp >= 840 && LocalDensity.current.fontScale <= 1.3f
    val density = UiDensity(compact, if (compact) 40.dp else 48.dp, if (compact) 12.dp else 16.dp,
        if (compact) 18.dp else 24.dp, if (compact) 16.dp else 18.dp, if (compact) 10.dp else 12.dp)
    val base = Typography()
    val typography = base.copy(
        headlineLarge = base.headlineLarge.copy(fontSize = if (compact) 22.sp else 26.sp, lineHeight = if (compact) 28.sp else 32.sp, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontSize = if (compact) 20.sp else 22.sp, lineHeight = if (compact) 26.sp else 28.sp, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontSize = if (compact) 16.sp else 18.sp, lineHeight = if (compact) 22.sp else 24.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = if (compact) 14.sp else 15.sp, lineHeight = if (compact) 20.sp else 21.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = if (compact) 13.sp else 14.sp, lineHeight = if (compact) 18.sp else 20.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = if (compact) 14.sp else 16.sp, lineHeight = if (compact) 21.sp else 24.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = if (compact) 13.sp else 14.sp, lineHeight = if (compact) 19.sp else 21.sp),
        bodySmall = base.bodySmall.copy(fontSize = if (compact) 11.sp else 12.sp, lineHeight = if (compact) 16.sp else 18.sp),
        labelLarge = base.labelLarge.copy(fontSize = if (compact) 13.sp else 14.sp, lineHeight = if (compact) 18.sp else 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontSize = if (compact) 11.sp else 12.sp, lineHeight = if (compact) 16.sp else 17.sp),
        labelSmall = base.labelSmall.copy(fontSize = if (compact) 10.sp else 11.sp, lineHeight = if (compact) 14.sp else 16.sp),
    )
    MaterialTheme(typography = typography, colorScheme = darkColorScheme(primary = BrandColor, onPrimary = Color.White,
        secondary = Color.White, background = CanvasColor, surface = SurfaceColor,
        surfaceVariant = RaisedColor, onSurface = Color.White, onSurfaceVariant = MutedColor,
        onBackground = Color.White, secondaryContainer = RaisedColor, onSecondaryContainer = Color.White,
        primaryContainer = RaisedColor, onPrimaryContainer = Color.White, surfaceTint = Color.Transparent,
        surfaceContainer = SurfaceColor, surfaceContainerLow = SurfaceColor, surfaceContainerHigh = RaisedColor,
        surfaceContainerHighest = ControlColor, surfaceContainerLowest = CanvasColor,
        outline = Color(0xff747474), error = Color(0xffff6670))) {
        CompositionLocalProvider(LocalUiDensity provides density, LocalReduceTransparency provides rememberReducedTransparency()) {
            Surface(Modifier.fillMaxSize(), color = CanvasColor, contentColor = Color.White, content = content)
        }
    }
}
@Composable fun Title(text: String, modifier: Modifier = Modifier, large: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    val compact = LocalUiDensity.current.compact
    Text(text, modifier.semantics { heading() }, color = MaterialTheme.colorScheme.onSurface,
        fontSize = (if (compact) if (large) 20 else 16 else if (large) 24 else 18).sp,
        lineHeight = (if (compact) if (large) 26 else 22 else if (large) 30 else 25).sp, fontWeight = FontWeight.SemiBold,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
@Composable fun Copy(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MutedColor, fontSize = if (LocalUiDensity.current.compact) 13.sp else 14.sp,
        lineHeight = if (LocalUiDensity.current.compact) 19.sp else 21.sp)
}
@Composable fun Glyph(@DrawableRes icon: Int, description: String?, modifier: Modifier = Modifier) {
    Icon(painterResource(icon), description, modifier.size(if (LocalUiDensity.current.compact) 18.dp else 20.dp))
}
@Composable fun IconAction(@DrawableRes icon: Int, description: String, modifier: Modifier = Modifier, click: () -> Unit) {
    IconButton(onClick = click, modifier = Modifier.minimumInteractiveComponentSize().size(if (LocalUiDensity.current.compact) 40.dp else 48.dp).then(modifier)) { Glyph(icon, description) }
}
@Composable fun TextAction(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val compact = LocalUiDensity.current.compact
    TextButton(onClick, modifier.minimumInteractiveComponentSize().heightIn(min = LocalUiDensity.current.controlHeight), enabled,
        contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 8.dp else 12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = MutedColor.copy(alpha = .5f)), content = content)
}
@Composable fun Action(text: String, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier,
    icon: Int? = null, selected: Boolean = false, click: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val compact = LocalUiDensity.current.compact
    Button(click, modifier.minimumInteractiveComponentSize().heightIn(min = LocalUiDensity.current.controlHeight).semantics { this.selected = selected }, enabled,
        shape = RoundedCornerShape(if (compact) 20.dp else 24.dp), interactionSource = interaction, elevation = null,
        contentPadding = PaddingValues(horizontal = if (compact) 14.dp else 16.dp, vertical = if (compact) 9.dp else 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) ControlFocusColor else if (primary) SelectedControlColor else if (selected) ActiveControlColor else ControlColor,
            contentColor = if (primary && !focused) Color.Black else Color.White,
            disabledContainerColor = ControlColor.copy(alpha = .55f), disabledContentColor = MutedColor.copy(alpha = .65f))) {
        val glyph = icon ?: R.drawable.ic_check.takeIf { selected }
        if (glyph != null) { Glyph(glyph, null, Modifier.size(if (compact) 16.dp else 18.dp)); Spacer(Modifier.width(if (compact) 6.dp else 8.dp)) }
        Text(text, fontSize = if (compact) 13.sp else 14.sp, lineHeight = if (compact) 18.sp else 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}
@Composable fun Choice(text: String, selected: Boolean = false, click: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val compact = LocalUiDensity.current.compact
    val horizontalPadding = if (LocalDensity.current.fontScale > 1.3f) 10.dp else if (compact) 12.dp else 14.dp
    val container by animateColorAsState(if (focused) ControlFocusColor else if (selected) SelectedControlColor else ControlColor, tween(120), label = "choice")
    val content = if (selected && !focused) Color.Black else Color.White
    Box(Modifier.minimumInteractiveComponentSize().heightIn(min = LocalUiDensity.current.controlHeight).clip(RoundedCornerShape(50))
        .selectable(selected, interactionSource = interaction, indication = LocalIndication.current, role = Role.Tab, onClick = click), contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = if (compact) 30.dp else 34.dp).clip(RoundedCornerShape(50)).background(container).padding(horizontal = horizontalPadding, vertical = if (compact) 6.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (selected) { CompositionLocalProvider(LocalContentColor provides content) { Glyph(R.drawable.ic_check, null, Modifier.size(if (compact) 12.dp else 14.dp)) }; Spacer(Modifier.width(6.dp)) }
            Text(text, color = content, fontSize = if (compact) 13.sp else 14.sp,
                lineHeight = if (compact) 18.sp else 20.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        }
    }
}
@Composable fun CheckRow(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = change)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null)
        Text(label, Modifier.weight(1f).padding(end = 8.dp))
    }
}
@Composable fun Selector(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, click: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val compact = LocalUiDensity.current.compact
    FilledTonalButton(click, modifier.minimumInteractiveComponentSize().heightIn(min = LocalUiDensity.current.controlHeight), enabled,
        shape = RoundedCornerShape(if (compact) 12.dp else 16.dp), interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 9.dp else 12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (focused) ControlFocusColor else ControlColor, contentColor = Color.White)) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = if (compact) 12.sp else 13.sp); Spacer(Modifier.width(if (compact) 6.dp else 8.dp)); Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(if (compact) 14.dp else 16.dp))
    }
}
@Composable fun Choices(values: List<String>, selected: Int, choose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "expandRotation")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceColor)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .clickable(role = Role.Button) { expanded = !expanded }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Title(title, Modifier.weight(1f)); Glyph(R.drawable.ic_chevron_down, if (expanded) "收起$title" else "展开$title",
                Modifier.graphicsLayer { rotationZ = rotation })
        }
        androidx.compose.animation.AnimatedVisibility(expanded,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
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
@Composable fun GlassSurface(modifier: Modifier = Modifier, radius: Int = 22, opaque: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.then(if (opaque) Modifier.contentPanel(radius) else Modifier.liquidSurface(radius)), content = content)
}
@Composable fun MediaCard(video: Video, modifier: Modifier = Modifier, menu: List<Pair<String, () -> Unit>> = emptyList(), progress: Float? = null, click: () -> Unit) {
    val site = SiteRegistry.byIdOrDefault(video.site)
    val personal = (LocalContext.current.applicationContext as R34Application).library.personal
    val personalRevision by personal.changes.collectAsState()
    val waiting = remember(personalRevision, video.entryKey) { personal.contains(video) }
    val shelfDialogs = remember { ComposeDialogs() }
    val actions = menu + listOf(
        (if (waiting) "移出稍后看" else "加入稍后看") to { if (waiting) personal.remove(video) else personal.add(video) },
        "加入合集" to { shelfDialogs.addToShelf(personal, video) },
    )
    val gallery = site.mediaKind == MediaKind.GALLERY || video.mediaKind == MediaKind.GALLERY
    var ratio by remember(video.site, video.id) { mutableFloatStateOf(if (gallery) video.thumbAspectRatio else 16f / 9f) }
    var expanded by remember { mutableStateOf(false) }
    val compact = LocalUiDensity.current.compact
    Column(modifier.testTag("media-${video.site}-${video.id}").combinedClickable(onClick = click, onLongClick = { expanded = true }).padding(bottom = if (compact) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
        Box(Modifier.clip(RoundedCornerShape(if (compact) 10.dp else 14.dp))) {
            SiteImage(video.thumb, site, Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(.45f, 1.8f)), gallery) { if (gallery && !video.hasThumbDimensions) ratio = it }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconAction(R.drawable.ic_more, "${video.title} 的更多操作", Modifier.background(Color.Black.copy(alpha = .65f), androidx.compose.foundation.shape.CircleShape)) { expanded = true }
                DropdownMenu(expanded, { expanded = false }) { actions.forEach { (label, action) -> DropdownMenuItem({ Text(label) }, { expanded = false; action() }) } }
            }
        }
        Text(video.title, fontSize = if (compact) 13.sp else 14.sp, lineHeight = if (compact) 19.sp else 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val metadata = listOf(mediaCountOrDuration(video), video.views, video.rating).filter(String::isNotBlank).joinToString(" · ")
        if (metadata.isNotBlank()) Text(metadata, color = MutedColor, fontSize = if (compact) 11.sp else 12.sp, lineHeight = if (compact) 16.sp else 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp),
            color = BrandColor, trackColor = Color.White.copy(alpha = .15f), gapSize = 0.dp, drawStopIndicator = {})
    }
    shelfDialogs.Host()
}

/** A single Compose dialog host shared by screens and update/download workflows. */
class ComposeDialogs {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
    fun dismiss() { content = null }
    @Composable fun Host() { content?.invoke() }
    fun message(title: String, body: String) { content = { AlertDialog(::dismiss, title = { Text(title) }, text = { Text(body, Modifier.verticalScroll(rememberScrollState())) },
        shape = RoundedCornerShape(24.dp), containerColor = SurfaceColor, tonalElevation = 0.dp,
        confirmButton = { TextAction(::dismiss) { Text("关闭") } }) } }
    fun confirm(title: String, positive: String = "确认", body: String = "", action: () -> Unit) { content = {
        AlertDialog(::dismiss, title = { Text(title) }, text = if (body.isNotBlank()) ({ Text(body, Modifier.verticalScroll(rememberScrollState())) }) else null,
            shape = RoundedCornerShape(24.dp), containerColor = SurfaceColor, tonalElevation = 0.dp,
            confirmButton = { TextAction({ dismiss(); action() }) { Text(positive) } }, dismissButton = { TextAction(::dismiss) { Text("取消") } })
    } }
    fun choose(title: String, values: List<String>, selected: Int = -1, action: (Int) -> Unit) { content = {
        ModalBottomSheet(::dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color.Transparent, scrimColor = Color.Black.copy(alpha = .68f), dragHandle = null) {
            GlassSurface(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp), radius = 28, opaque = true) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 4.dp).background(Color.White.copy(alpha = .3f), RoundedCornerShape(50)))
                    Title(title, Modifier.padding(top = 16.dp, bottom = 8.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).selectableGroup()) {
                        values.forEachIndexed { i, value -> Row(Modifier.fillMaxWidth().heightIn(min = if (LocalUiDensity.current.compact) 48.dp else 50.dp)
                            .clip(RoundedCornerShape(if (LocalUiDensity.current.compact) 12.dp else 14.dp)).background(if (selected == i) Color.White.copy(alpha = .09f) else Color.Transparent)
                            .then(if (selected >= 0) Modifier.selectable(selected == i, role = Role.RadioButton) { dismiss(); action(i) }
                                else Modifier.clickable(role = Role.Button) { dismiss(); action(i) })
                            .padding(horizontal = LocalUiDensity.current.contentPadding), verticalAlignment = Alignment.CenterVertically) {
                            Text(value, Modifier.weight(1f).padding(vertical = if (LocalUiDensity.current.compact) 10.dp else 14.dp), fontSize = if (LocalUiDensity.current.compact) 12.sp else 13.sp,
                                fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Normal)
                            if (selected == i) Glyph(R.drawable.ic_check, null, Modifier.size(if (LocalUiDensity.current.compact) 18.dp else 20.dp))
                        } }
                    }
                    TextAction(::dismiss, Modifier.align(Alignment.End)) { Text("取消") }
                }
            }
        }
    } }
    fun edit(title: String, initial: String = "", hint: String = "", keyboard: KeyboardType = KeyboardType.Text, validate: (String) -> String? = { null }, action: (String) -> Unit) { content = {
        var value by remember { mutableStateOf(initial) }; var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(::dismiss, title = { Text(title) }, text = { Column {
            TextField(value, { value = it; error = null }, Modifier.fillMaxWidth(), label = { Text(hint.ifBlank { title }) },
                shape = RoundedCornerShape(16.dp), colors = filledFieldColors(), isError = error != null, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, shape = RoundedCornerShape(24.dp), containerColor = SurfaceColor, tonalElevation = 0.dp,
            confirmButton = { TextAction({ error = validate(value.trim()); if (error == null) { dismiss(); action(value.trim()) } }) { Text("确定") } }, dismissButton = { TextAction(::dismiss) { Text("取消") } })
    } }
}
