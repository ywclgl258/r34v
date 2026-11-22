package com.r34v.r34v.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r34v.r34v.R
import com.r34v.r34v.data.*

fun mediaCountOrDuration(video: Video): String = video.duration.takeUnless {
    (video.mediaKind == MediaKind.GALLERY || SiteRegistry.byIdOrDefault(video.site).mediaKind == MediaKind.GALLERY) &&
        it.trim().matches(Regex("\\d{1,3}:\\d{2}(:\\d{2})?"))
}.orEmpty()

@Composable fun MediaResumeHero(video: Video, status: String, action: String, play: (Video) -> Unit) {
    val site = SiteRegistry.byIdOrDefault(video.site)
    val category = if (video.mediaKind == MediaKind.GALLERY && site.category == SiteCategory.VIDEO) SiteCategory.PHOTO else site.category
    val (stageTag, actionIcon, ratio) = when (category) {
        SiteCategory.VIDEO -> Triple("video-stage", R.drawable.ic_play, 16f / 9f)
        SiteCategory.COMIC -> Triple("comic-stage", R.drawable.ic_bookmark, .72f)
        SiteCategory.PHOTO -> Triple("photo-stage", R.drawable.ic_explore, .82f)
    }
    val metadata = listOf(status, mediaCountOrDuration(video)).filter(String::isNotBlank).distinct().joinToString("  ·  ")
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(stageTag)) {
        val wide = maxWidth >= 640.dp
        val coverWidth = if (wide) {
            if (category == SiteCategory.VIDEO) 272.dp else 146.dp
        } else {
            if (category == SiteCategory.VIDEO) (maxWidth * .36f).coerceAtMost(140.dp) else (maxWidth * .34f).coerceAtMost(116.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (wide) 22.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SiteImage(video.thumb, site, Modifier.width(coverWidth).aspectRatio(ratio).clip(RoundedCornerShape(10.dp)),
                    fit = category != SiteCategory.VIDEO)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(video.title, Modifier.semantics { heading() }, color = MaterialTheme.colorScheme.onSurface,
                        fontSize = if (wide) 30.sp else 23.sp, lineHeight = if (wide) 36.sp else 28.sp,
                        fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (metadata.isNotBlank()) Text(metadata, color = MutedColor, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (wide) Action(action, Modifier.padding(top = 4.dp).widthIn(max = 320.dp).fillMaxWidth().heightIn(min = 48.dp),
                        primary = true, icon = actionIcon) { play(video) }
                }
            }
            if (!wide) Action(action, Modifier.fillMaxWidth().heightIn(min = 48.dp), primary = true, icon = actionIcon) { play(video) }
        }
    }
}
