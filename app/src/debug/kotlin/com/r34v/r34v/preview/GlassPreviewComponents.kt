package com.r34v.r34v.preview

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r34v.r34v.R

internal val PreviewCanvas = Color(0xFF050607)
internal val PreviewMatte = Color(0xFF14171B)
internal val PreviewMuted = Color(0xFFA9ADB4)
internal val PreviewRed = Color(0xFFE50914)

@Composable
internal fun PreviewIcon(@DrawableRes icon: Int, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(icon), description, modifier.size(20.dp), tint = LocalContentColor.current)
}

@Composable
internal fun GlassPanel(
    modifier: Modifier = Modifier,
    bitmap: ImageBitmap? = null,
    radius: Dp = 22.dp,
    elevated: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.shadow(if (elevated) 18.dp else 0.dp, shape, clip = false).clip(shape)) {
        if (bitmap != null) {
            val blur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.blur(28.dp, BlurredEdgeTreatment(shape))
            } else Modifier
            Image(bitmap, null, Modifier.matchParentSize().then(blur), contentScale = ContentScale.Crop)
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(Color(0xD117191D), Color(0xEB0C0E11)),
        )))
        Box(Modifier.matchParentSize().border(
            0.8.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.White.copy(alpha = .035f))),
            shape,
        ))
        content()
    }
}

@Composable
internal fun PreviewIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = description, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = .075f))
            .border(.7.dp, Color.White.copy(alpha = .13f), CircleShape), contentAlignment = Alignment.Center) {
            PreviewIcon(icon, description, Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun PhysicalButton(
    text: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .72f, stiffness = 380f), label = "scale")
    val radius by animateDpAsState(if (pressed) 11.dp else 14.dp, tween(160), label = "radius")
    val elevation by animateDpAsState(if (pressed) 2.dp else 7.dp, label = "elevation")
    val container by animateColorAsState(when {
        primary -> Color.White
        selected -> PreviewRed.copy(alpha = .24f)
        else -> Color(0xC82A2E34)
    }, label = "container")
    val shape = RoundedCornerShape(radius)
    Row(
        modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation, shape, clip = false).clip(shape).background(container)
            .border(.8.dp, if (selected) PreviewRed.copy(alpha = .62f) else Color.White.copy(alpha = .16f), shape)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides if (primary) Color.Black else Color.White) {
            Row(Modifier.padding(horizontal = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                Crossfade(icon, label = "icon") { current ->
                    PreviewIcon(current, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(7.dp))
                Crossfade(text, label = "text") { current ->
                    Text(current, color = if (primary) Color.Black else Color.White, fontSize = 12.sp,
                        lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(if (selected) Color.White.copy(alpha = .94f) else Color(0xFF171A1F).copy(alpha = .72f), label = "chip")
    val content by animateColorAsState(if (selected) Color.Black else PreviewMuted, label = "content")
    Box(Modifier.height(48.dp).clip(RoundedCornerShape(17.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(container)
            .border(.7.dp, if (selected) Color.White.copy(alpha = .5f) else Color.White.copy(alpha = .13f), RoundedCornerShape(17.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, Modifier.padding(horizontal = 14.dp), color = content, fontSize = 11.sp,
                lineHeight = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        }
    }
}

@Composable
internal fun SectionHeading(title: String, subtitle: String = "") {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotBlank()) Text(subtitle, color = PreviewMuted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
internal fun PosterCard(
    title: String,
    bitmap: ImageBitmap,
    rank: Int? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, label = "poster")
    Column(Modifier.width(110.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClickLabel = "播放 $title", onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).shadow(10.dp, RoundedCornerShape(14.dp), clip = false)
            .clip(RoundedCornerShape(14.dp)).border(.7.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(14.dp))) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (rank != null) GlassPanel(Modifier.align(Alignment.TopStart).padding(7.dp).size(30.dp), bitmap, radius = 10.dp, elevated = false) {
                Text("$rank", Modifier.align(Alignment.Center), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(title, Modifier.padding(top = 7.dp), color = Color.White, fontSize = 11.sp, lineHeight = 16.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("2026 · 剧集", Modifier.padding(top = 2.dp), color = PreviewMuted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
    }
}

@Composable
internal fun ContinueCard(title: String, bitmap: ImageBitmap, progress: Float, onClick: () -> Unit) {
    Column(Modifier.width(184.dp).shadow(12.dp, RoundedCornerShape(17.dp), clip = false)
        .clip(RoundedCornerShape(17.dp)).background(PreviewMatte)
        .border(.8.dp, Color.White.copy(alpha = .1f), RoundedCornerShape(17.dp))
        .clickable(onClickLabel = "继续观看 $title", onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .16f)))
            GlassPanel(Modifier.align(Alignment.Center).size(38.dp), bitmap, radius = 19.dp, elevated = false) {
                PreviewIcon(R.drawable.ic_play, null, Modifier.align(Alignment.Center).size(18.dp))
            }
        }
        LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(2.dp),
            color = PreviewRed, trackColor = Color.White.copy(alpha = .13f), gapSize = 0.dp, drawStopIndicator = {})
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color.White, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("剩余 26 分钟", color = PreviewMuted, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

internal data class DockItem(val key: String, val label: String, @DrawableRes val icon: Int)

@Composable
internal fun FloatingDock(
    items: List<DockItem>,
    selected: String,
    bitmap: ImageBitmap,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 66.dp,
) {
    GlassPanel(modifier.fillMaxWidth().height(height), bitmap, radius = 23.dp) {
        Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            items.forEach { item ->
                val active = item.key == selected
                val background by animateColorAsState(if (active) Color.White.copy(alpha = .13f) else Color.Transparent, label = "tab")
                val content by animateColorAsState(if (active) Color.White else PreviewMuted, label = "tabContent")
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(17.dp)).background(background)
                    .selectable(active, role = Role.Tab) { onSelect(item.key) }) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides content) {
                            PreviewIcon(item.icon, null, Modifier.size(19.dp))
                            Text(item.label, color = content, fontSize = 10.sp, lineHeight = 13.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}
