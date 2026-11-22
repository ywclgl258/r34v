@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.r34v.r34v.ui

import androidx.annotation.DrawableRes
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

val CanvasColor = RentalInk
val SurfaceColor = RentalSurface
val RaisedColor = Color(0xff252e32)
val ControlColor = Color(0xff30393c)
val ControlFocusColor = Color(0xff485459)
val SelectedControlColor = RentalPaper
val MutedColor = RentalMuted
val BrandColor = RentalRed
val MatchColor = Color(0xff46d369)
val GlassTopColor = Color(0xee202020)
val GlassBottomColor = Color(0xf0121212)

@Composable fun filledFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContainerColor = SurfaceColor, errorContainerColor = Color(0xff301417),
    focusedIndicatorColor = RentalRed, unfocusedIndicatorColor = RentalEdge,
    disabledIndicatorColor = Color.Transparent, errorIndicatorColor = MaterialTheme.colorScheme.error,
    cursorColor = MaterialTheme.colorScheme.secondary, focusedLabelColor = MaterialTheme.colorScheme.onSurface, unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant)

data class UiDensity(val compact: Boolean, val controlHeight: androidx.compose.ui.unit.Dp,
    val contentPadding: androidx.compose.ui.unit.Dp, val sectionGap: androidx.compose.ui.unit.Dp,
    val pagePadding: androidx.compose.ui.unit.Dp, val cardGap: androidx.compose.ui.unit.Dp)
val LocalUiDensity = staticCompositionLocalOf { UiDensity(false, 48.dp, 16.dp, 24.dp, 20.dp, 12.dp) }

@Composable fun R34Theme(content: @Composable () -> Unit) {
    val compact = windowSizeDp().width >= 840 && LocalDensity.current.fontScale <= 1.3f
    val density = UiDensity(compact, if (compact) 38.dp else 48.dp, if (compact) 10.dp else 16.dp,
        if (compact) 20.dp else 24.dp, if (compact) 16.dp else 20.dp, if (compact) 10.dp else 12.dp)
    val base = Typography()
    val typography = base.copy(
        headlineLarge = base.headlineLarge.copy(fontSize = if (compact) 25.sp else 30.sp, lineHeight = if (compact) 31.sp else 36.sp, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontSize = if (compact) 21.sp else 25.sp, lineHeight = if (compact) 26.sp else 32.sp, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontSize = if (compact) 17.sp else 20.sp, lineHeight = if (compact) 22.sp else 27.sp, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontSize = if (compact) 13.sp else 15.sp, lineHeight = if (compact) 18.sp else 21.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = if (compact) 12.sp else 14.sp, lineHeight = if (compact) 17.sp else 20.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = if (compact) 13.sp else 16.sp, lineHeight = if (compact) 19.sp else 24.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = if (compact) 12.sp else 14.sp, lineHeight = if (compact) 17.sp else 21.sp),
        bodySmall = base.bodySmall.copy(fontSize = if (compact) 10.sp else 12.sp, lineHeight = if (compact) 15.sp else 18.sp),
        labelLarge = base.labelLarge.copy(fontSize = if (compact) 12.sp else 14.sp, lineHeight = if (compact) 17.sp else 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontSize = if (compact) 10.sp else 12.sp, lineHeight = if (compact) 15.sp else 17.sp),
        labelSmall = base.labelSmall.copy(fontSize = if (compact) 9.sp else 11.sp, lineHeight = if (compact) 13.sp else 16.sp),
    )
    MaterialTheme(typography = typography, shapes = Shapes(
        extraSmall = RentalShape, small = RentalShape, medium = RoundedCornerShape(6.dp),
        large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(8.dp)), colorScheme = darkColorScheme(primary = BrandColor, onPrimary = CanvasColor,
        secondary = SelectedControlColor, onSecondary = CanvasColor, background = CanvasColor, surface = SurfaceColor,
        surfaceVariant = RaisedColor, onSurface = SelectedControlColor, onSurfaceVariant = MutedColor,
        onBackground = SelectedControlColor, secondaryContainer = RaisedColor, onSecondaryContainer = SelectedControlColor,
        primaryContainer = RaisedColor, onPrimaryContainer = SelectedControlColor, surfaceTint = Color.Transparent,
        surfaceContainer = SurfaceColor, surfaceContainerLow = SurfaceColor, surfaceContainerHigh = RaisedColor,
        surfaceContainerHighest = ControlColor, surfaceContainerLowest = CanvasColor,
        outline = RentalEdge, outlineVariant = Color(0xff485052), error = Color(0xffff6670))) {
        CompositionLocalProvider(LocalUiDensity provides density, LocalReduceTransparency provides rememberReducedTransparency(),
            LocalMotionEnabled provides rememberSystemMotionEnabled()) {
            Surface(Modifier.fillMaxSize(), color = CanvasColor, contentColor = SelectedControlColor, content = content)
        }
    }
}
@Composable fun Title(text: String, modifier: Modifier = Modifier, large: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier.semantics { heading() }, color = MaterialTheme.colorScheme.onSurface,
        style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
@Composable fun Copy(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = if (LocalUiDensity.current.compact) 11.5.sp else 14.sp,
        lineHeight = if (LocalUiDensity.current.compact) 16.sp else 21.sp)
}
@Composable fun Glyph(@DrawableRes icon: Int, description: String?, modifier: Modifier = Modifier) {
    Icon(painterResource(icon), description, modifier.size(if (LocalUiDensity.current.compact) 17.dp else 20.dp))
}
@Composable fun IconAction(@DrawableRes icon: Int, description: String, modifier: Modifier = Modifier, click: () -> Unit) {
    IconButton(onClick = click, modifier = Modifier.minimumInteractiveComponentSize().size(if (LocalUiDensity.current.compact) 40.dp else 48.dp).then(modifier)) { Glyph(icon, description) }
}
@Composable fun TextAction(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val compact = LocalUiDensity.current.compact
    TextButton(onClick, modifier.minimumInteractiveComponentSize().heightIn(min = LocalUiDensity.current.controlHeight), enabled,
        shape = RentalShape,
        contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 6.dp else 12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)), content = content)
}
@Composable fun Action(text: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true,
    icon: Int? = null, selected: Boolean? = null, click: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val compact = LocalUiDensity.current.compact
    val colors = MaterialTheme.colorScheme
    Button(click, modifier.minimumInteractiveComponentSize().heightIn(min = LocalUiDensity.current.controlHeight).rentalKeyPress(interaction).semantics { if (selected != null) this.selected = selected }, enabled,
        shape = RentalShape, interactionSource = interaction, elevation = null,
        border = if (!primary) RentalBorder else null,
        contentPadding = PaddingValues(horizontal = if (compact) 11.dp else 16.dp, vertical = if (compact) 7.dp else 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when { focused -> ControlFocusColor; primary -> colors.secondary; selected == true -> RentalPaper; else -> colors.surfaceContainerHighest },
            contentColor = if ((primary || selected == true) && !focused) colors.background else colors.onSurface,
            disabledContainerColor = ControlColor.copy(alpha = .55f), disabledContentColor = MutedColor.copy(alpha = .65f))) {
        val glyph = icon ?: R.drawable.ic_check.takeIf { selected == true }
        if (glyph != null) { Glyph(glyph, null, Modifier.size(if (compact) 16.dp else 18.dp)); Spacer(Modifier.width(if (compact) 6.dp else 8.dp)) }
        Text(text, fontSize = if (compact) 12.sp else 14.sp, lineHeight = if (compact) 16.sp else 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}
@Composable fun Choice(text: String, selected: Boolean = false, click: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val compact = LocalUiDensity.current.compact
    val horizontalPadding = if (LocalDensity.current.fontScale > 1.3f) 10.dp else if (compact) 12.dp else 14.dp
    val colors = MaterialTheme.colorScheme
    val shape = RentalShape
    val container = if (focused) ControlFocusColor else if (selected) colors.secondary else colors.surfaceContainerHigh
    val content = if (selected && !focused) colors.background else colors.onSurface
    Box(Modifier.minimumInteractiveComponentSize().heightIn(min = LocalUiDensity.current.controlHeight).rentalKeyPress(interaction).clip(shape)
        .selectable(selected, interactionSource = interaction, indication = LocalIndication.current, role = Role.Tab, onClick = click), contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = if (compact) 27.dp else 34.dp).clip(shape).background(container)
            .then(if (!selected) Modifier.border(RentalBorder, shape) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = if (compact) 4.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (selected) { CompositionLocalProvider(LocalContentColor provides content) { Glyph(R.drawable.ic_check, null, Modifier.size(if (compact) 12.dp else 14.dp)) }; Spacer(Modifier.width(6.dp)) }
            Text(text, color = content, fontSize = if (compact) 12.sp else 14.sp,
                lineHeight = if (compact) 16.sp else 20.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
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
@Composable fun Selector(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, click: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val compact = LocalUiDensity.current.compact
    FilledTonalButton(click, modifier.rentalKeyPress(interaction).minimumInteractiveComponentSize().heightIn(min = LocalUiDensity.current.controlHeight), enabled,
        shape = RentalShape, interactionSource = interaction,
        border = RentalBorder,
        contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 9.dp else 12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (focused) ControlFocusColor else MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = if (compact) 12.sp else 13.sp); Spacer(Modifier.width(if (compact) 6.dp else 8.dp)); Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(if (compact) 14.dp else 16.dp))
    }
}
@Composable fun Choices(values: List<String>, selected: Int, choose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEachIndexed { i, text -> Choice(text, i == selected) { choose(i) } }
    }
}
@Composable fun Loading(modifier: Modifier = Modifier, title: String = "正在加载内容") {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = BrandColor, modifier = Modifier.size(28.dp)); Copy(title)
    }
}
@Composable fun EmptyState(title: String, body: String, action: String = "重试", click: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).background(RentalSurface, RentalShape)
        .border(RentalBorder, RentalShape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Title(title)
        Copy(body)
        Action(action, primary = true, click = click)
    }
}
@Composable fun Expandable(title: String, expandedInitially: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(expandedInitially) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "expandRotation")
    val panel = Modifier.clip(RentalShape).background(RentalSurface).border(RentalBorder, RentalShape)
    Column(Modifier.fillMaxWidth().then(panel)) {
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
        val allowed = url.startsWith("https://") || com.r34v.r34v.BuildConfig.DEBUG && url.startsWith("http://") ||
            site.id == SiteRegistry.LOCAL.id && app.localLibrary.ownsImageUri(url)
        ImageRequest.Builder(context).data(url.takeIf { allowed }).memoryCacheKey("${site.id}|$url").diskCacheKey("${site.id}|$url").build()
    }
    Box(modifier.background(Brush.verticalGradient(listOf(RaisedColor, SurfaceColor)))) {
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
@Composable fun MediaCard(video: Video, modifier: Modifier = Modifier, menu: List<Pair<String, () -> Unit>> = emptyList(),
    progress: Float? = null, progressLabel: String? = null, quietSleeve: Boolean = false, click: () -> Unit) {
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
    val caseShape = RentalShape
    val duration = mediaCountOrDuration(video)
    val metadata = progressLabel ?: if (quietSleeve) duration
        else listOf(duration, video.views, video.rating).filter(String::isNotBlank).joinToString(" · ")
    val outline = if (quietSleeve) BorderStroke(1.dp, RentalEdge.copy(alpha = .27f)) else RentalBorder
    val labelColor = if (quietSleeve) RentalPaper else RentalInk
    Column(modifier.testTag("media-${video.site}-${video.id}").clip(caseShape).background(RentalSurface).border(outline, caseShape)
        .combinedClickable(role = Role.Button, onClick = click, onLongClickLabel = "更多作品操作", onLongClick = { expanded = true })
        .padding(1.dp)) {
        Box {
            SiteImage(video.thumb, site, Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(.45f, 1.8f)), gallery) { if (gallery && !video.hasThumbDimensions) ratio = it }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { expanded = true }) {
                    Box(Modifier.size(25.dp).background(RentalInk.copy(alpha = .82f), caseShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_more), "${video.title} 的更多操作", Modifier.size(17.dp), tint = RentalPaper)
                    }
                }
                DropdownMenu(expanded, { expanded = false }) { actions.forEach { (label, action) -> DropdownMenuItem({ Text(label) }, { expanded = false; action() }) } }
            }
        }
        val label = if (quietSleeve) Modifier else Modifier.background(RentalPaper).rentalStock()
        Column(Modifier.fillMaxWidth().then(label).padding(horizontal = 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(video.title, color = labelColor, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (metadata.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(metadata, Modifier.weight(1f), color = if (quietSleeve) RentalMuted else RentalInk.copy(alpha = .78f), fontSize = 11.sp,
                    lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!quietSleeve) RentalStripes(Modifier.size(18.dp, 7.dp))
            }
        }
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp),
            color = BrandColor, trackColor = RentalEdge, gapSize = 0.dp, drawStopIndicator = {})
    }
    shelfDialogs.Host()
}

/** A single Compose dialog host shared by screens and update/download workflows. */
class ComposeDialogs {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
    fun dismiss() { content = null }
    @Composable fun Host() { content?.invoke() }
    fun message(title: String, body: String) { content = { AlertDialog(::dismiss, title = { Text(title) }, text = { Text(body, Modifier.verticalScroll(rememberScrollState())) },
        shape = RentalShape, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp,
        confirmButton = { TextAction(::dismiss) { Text("关闭") } }) } }
    fun confirm(title: String, positive: String = "确认", body: String = "", action: () -> Unit) { content = {
        AlertDialog(::dismiss, title = { Text(title) }, text = if (body.isNotBlank()) ({ Text(body, Modifier.verticalScroll(rememberScrollState())) }) else null,
            shape = RentalShape, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp,
            confirmButton = { TextAction({ dismiss(); action() }) { Text(positive) } }, dismissButton = { TextAction(::dismiss) { Text("取消") } })
    } }
    fun choose(title: String, values: List<String>, selected: Int = -1, action: (Int) -> Unit) { content = {
        ModalBottomSheet(::dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color.Transparent, scrimColor = Color.Black.copy(alpha = .68f), dragHandle = null) {
            val panel = Modifier.clip(RentalShape).background(RentalInk).border(RentalBorder, RentalShape)
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).then(panel)) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 4.dp)
                        .background(RentalMuted, RoundedCornerShape(50)))
                    Title(title, Modifier.padding(top = 16.dp, bottom = 8.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).selectableGroup()) {
                        values.forEachIndexed { i, value ->
                            val fill = if (selected != i) Color.Transparent else RentalPaper
                            val ink = if (selected == i) RentalInk else MaterialTheme.colorScheme.onSurface
                            Row(Modifier.fillMaxWidth().heightIn(min = if (LocalUiDensity.current.compact) 48.dp else 50.dp)
                            .clip(RentalShape).background(fill)
                            .then(if (selected >= 0) Modifier.selectable(selected == i, role = Role.RadioButton) { dismiss(); action(i) }
                                else Modifier.clickable(role = Role.Button) { dismiss(); action(i) })
                            .padding(horizontal = LocalUiDensity.current.contentPadding), verticalAlignment = Alignment.CenterVertically) {
                            Text(value, Modifier.weight(1f).padding(vertical = if (LocalUiDensity.current.compact) 10.dp else 14.dp), color = ink, fontSize = if (LocalUiDensity.current.compact) 12.sp else 13.sp,
                                fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Normal)
                            if (selected == i) CompositionLocalProvider(LocalContentColor provides ink) {
                                Glyph(R.drawable.ic_check, null, Modifier.size(if (LocalUiDensity.current.compact) 18.dp else 20.dp))
                            }
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
                shape = RentalShape, colors = filledFieldColors(), isError = error != null, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, shape = RentalShape, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp,
            confirmButton = { TextAction({ error = validate(value.trim()); if (error == null) { dismiss(); action(value.trim()) } }) { Text("确定") } }, dismissButton = { TextAction(::dismiss) { Text("取消") } })
    } }
}
