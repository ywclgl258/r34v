package com.r34v.r34v.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val shape = RoundedCornerShape(4.dp)
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            .clip(shape).background(RentalSurface).border(1.dp, RentalEdge, shape).padding(3.dp),
    ) {
        val separate = config.fontScale > 1.3f || maxWidth < 320.dp
        val wide = maxWidth >= 640.dp
        val fitCover = site.category == SiteCategory.COMIC
        val action = "${if (entry.started) "继续" else "开始"}${site.category.verb}"
        val tag = when (site.category) { SiteCategory.VIDEO -> "video-stage"; SiteCategory.COMIC -> "comic-stage"; SiteCategory.PHOTO -> "photo-stage" }
        val icon = when (site.category) { SiteCategory.VIDEO -> R.drawable.ic_play; SiteCategory.COMIC -> R.drawable.ic_bookmark; SiteCategory.PHOTO -> R.drawable.ic_explore }
        val info: @Composable ColumnScope.() -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(site.name, Modifier.weight(1f), color = RentalInk.copy(alpha = .76f),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                RentalStripes(Modifier.size(24.dp, 9.dp))
            }
            Text(entry.video.title, Modifier.semantics { heading() }, color = RentalInk, fontSize = if (wide) 28.sp else 21.sp,
                lineHeight = if (wide) 33.sp else 25.sp, fontWeight = FontWeight.Black,
                maxLines = if (separate) 4 else 3, overflow = TextOverflow.Ellipsis)
            if (entry.started) {
                Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.progress, color = RentalInk, style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(progress = { entry.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = RentalInk, trackColor = RentalInk.copy(alpha = .2f), gapSize = 0.dp, drawStopIndicator = {})
                }
            } else {
                Text("来自稍后看", color = RentalInk.copy(alpha = .76f), style = MaterialTheme.typography.labelMedium)
            }
            PersonalCardAction(action, icon) { play(entry.video) }
        }
        val paper = Modifier.background(RentalPaper).rentalStock()
        if (separate) {
            Column(Modifier.fillMaxWidth().testTag(tag)) {
                SiteImage(entry.video.thumb, site, Modifier.fillMaxWidth().height(if (fitCover) 206.dp else 150.dp), fit = fitCover)
                Column(Modifier.fillMaxWidth().then(paper).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = info)
            }
        } else {
            val imageFraction = if (wide) .5f else .42f
            Box(Modifier.fillMaxWidth().testTag(tag)) {
                Box(Modifier.matchParentSize()) {
                    SiteImage(entry.video.thumb, site, Modifier.fillMaxWidth(imageFraction).fillMaxHeight(), fit = fitCover)
                }
                Column(Modifier.align(Alignment.CenterEnd).fillMaxWidth(1f - imageFraction).then(paper)
                    .heightIn(min = if (wide) 224.dp else 174.dp).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically), content = info)
            }
        }
    }
}

@Composable private fun PersonalCardAction(label: String, icon: Int, click: () -> Unit) {
    Button(onClick = click, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(3.dp),
        colors = ButtonDefaults.buttonColors(containerColor = RentalInk, contentColor = RentalPaper),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        Glyph(icon, null, Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = FontWeight.Bold)
    }
}
