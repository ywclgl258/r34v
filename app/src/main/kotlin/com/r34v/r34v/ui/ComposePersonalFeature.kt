package com.r34v.r34v.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r34v.r34v.R
import com.r34v.r34v.data.*

@Composable fun PersonalFeature(entry: CatalogEntry, play: (Video) -> Unit) {
    val config = LocalConfiguration.current
    val site = SiteRegistry.byIdOrDefault(entry.video.site)
    val palette = rememberArtworkPalette(site, entry.video.thumb, cachedOnly = entry.offline)
    ArtworkColors(palette) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            .clip(ArtworkShape).artworkBackground(palette).border(1.dp, palette.edge, ArtworkShape).padding(1.dp),
    ) {
        val separate = config.fontScale > 1.3f || maxWidth < 320.dp
        val wide = maxWidth >= 640.dp
        val fitCover = site.category != SiteCategory.VIDEO || entry.video.mediaKind == MediaKind.GALLERY
        val action = "${if (entry.started) "继续" else "开始"}${site.category.verb}"
        val tag = when (site.category) { SiteCategory.VIDEO -> "video-stage"; SiteCategory.COMIC -> "comic-stage"; SiteCategory.PHOTO -> "photo-stage" }
        val icon = when (site.category) { SiteCategory.VIDEO -> R.drawable.ic_play; SiteCategory.COMIC -> R.drawable.ic_bookmark; SiteCategory.PHOTO -> R.drawable.ic_explore }
        val info: @Composable ColumnScope.() -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(site.name, Modifier.weight(1f), color = palette.muted,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(entry.video.title, Modifier.semantics { heading() }, color = palette.ink, fontSize = if (wide) 28.sp else 21.sp,
                lineHeight = if (wide) 33.sp else 25.sp, fontWeight = FontWeight.Black,
                maxLines = if (separate) 4 else 3, overflow = TextOverflow.Ellipsis)
            if (entry.started) {
                Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.progress, color = palette.muted, style = MaterialTheme.typography.labelMedium)
                    entry.fraction?.let { fraction ->
                        LinearProgressIndicator(progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = palette.accent, trackColor = palette.edge, gapSize = 0.dp, drawStopIndicator = {})
                    }
                }
            } else {
                Text("来自稍后看", color = palette.muted, style = MaterialTheme.typography.labelMedium)
            }
            ArtworkAction(action, icon = icon) { play(entry.video) }
        }
        if (separate) {
            Column(Modifier.fillMaxWidth().testTag(tag)) {
                SiteImage(entry.video.thumb, site, Modifier.fillMaxWidth().height(if (fitCover) 206.dp else 150.dp), fit = fitCover)
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = info)
            }
        } else {
            val imageFraction = if (wide) .5f else .42f
            Box(Modifier.fillMaxWidth().testTag(tag)) {
                Box(Modifier.matchParentSize()) {
                    SiteImage(entry.video.thumb, site, Modifier.fillMaxWidth(imageFraction).fillMaxHeight(), fit = fitCover)
                }
                Column(Modifier.align(Alignment.CenterEnd).fillMaxWidth(1f - imageFraction)
                    .heightIn(min = if (wide) 224.dp else 174.dp).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically), content = info)
            }
        }
    }
    }
}
