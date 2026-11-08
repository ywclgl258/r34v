package com.r34v.r34v.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r34v.r34v.R
import com.r34v.r34v.data.*

/** The personal home feature is part of the canvas, not another management card. */
@Composable fun PersonalFeature(entry: CatalogEntry, play: (Video) -> Unit) {
    val config = LocalConfiguration.current
    val site = SiteRegistry.byIdOrDefault(entry.video.site)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 700.dp && config.fontScale <= 1.3f
        val separate = config.fontScale > 1.3f || maxWidth < 360.dp
        val comic = site.category == SiteCategory.COMIC
        val action = "${if (entry.started) "继续" else "开始"}${site.category.verb}"
        val tag = when (site.category) { SiteCategory.VIDEO -> "video-stage"; SiteCategory.COMIC -> "comic-stage"; SiteCategory.PHOTO -> "photo-stage" }
        val icon = when (site.category) { SiteCategory.VIDEO -> R.drawable.ic_play; SiteCategory.COMIC -> R.drawable.ic_bookmark; SiteCategory.PHOTO -> R.drawable.ic_explore }
        val info: @Composable ColumnScope.() -> Unit = {
            Text("${if (entry.started) "接着${site.category.verb}" else "来自稍后看"} · ${site.name}", color = MutedColor, style = MaterialTheme.typography.labelMedium)
            Text(entry.video.title, color = Color.White, fontSize = if (separate) 24.sp else 30.sp,
                lineHeight = if (separate) 32.sp else 38.sp, fontWeight = FontWeight.Bold,
                maxLines = if (separate) 4 else 3, overflow = TextOverflow.Ellipsis)
            if (entry.started) {
                Text(entry.progress, color = MutedColor, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { entry.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = BrandColor, trackColor = ControlColor, gapSize = 0.dp, drawStopIndicator = {})
            }
            Action(action, primary = true, icon = icon, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { play(entry.video) }
        }
        when {
            wide -> Row(Modifier.fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1.2f).height(280.dp)) {
                    SiteImage(entry.video.thumb, site, Modifier.fillMaxSize(), fit = comic)
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, CanvasColor))))
                }
                Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = info)
            }
            comic || separate -> Column(Modifier.fillMaxWidth().testTag(tag)) {
                Box(Modifier.fillMaxWidth().height(if (comic) 248.dp else 190.dp)) {
                    SiteImage(entry.video.thumb, site, Modifier.fillMaxSize(), fit = comic)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.65f to Color.Transparent, 1f to CanvasColor)))
                }
                Column(Modifier.padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), content = info)
            }
            else -> Box(Modifier.fillMaxWidth().testTag(tag)) {
                SiteImage(entry.video.thumb, site, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .1f),
                    .35f to Color.Black.copy(alpha = .12f), .65f to Color.Black.copy(alpha = .84f), 1f to CanvasColor)))
                Column(Modifier.fillMaxWidth().heightIn(min = 330.dp).padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom), content = info)
            }
        }
    }
}
