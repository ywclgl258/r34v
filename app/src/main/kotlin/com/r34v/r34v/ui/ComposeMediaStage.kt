package com.r34v.r34v.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R
import com.r34v.r34v.data.*

fun mediaCountOrDuration(video: Video): String = video.duration.takeUnless {
    (video.mediaKind == MediaKind.GALLERY || SiteRegistry.byIdOrDefault(video.site).mediaKind == MediaKind.GALLERY) &&
        it.trim().matches(Regex("\\d{1,3}:\\d{2}(:\\d{2})?"))
}.orEmpty()

/** One primary object, with a shape that belongs to its medium. No material over content. */
@Composable fun MediaResumeHero(video: Video, status: String, action: String, play: (Video) -> Unit) {
    val site = SiteRegistry.byIdOrDefault(video.site)
    val compact = LocalConfiguration.current.screenWidthDp < 360 || LocalConfiguration.current.fontScale > 1.3f
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val actionIcon = when (site.category) {
        SiteCategory.VIDEO -> R.drawable.ic_play
        SiteCategory.COMIC -> R.drawable.ic_bookmark
        SiteCategory.PHOTO -> R.drawable.ic_explore
    }
    if (site.category == SiteCategory.COMIC && wide) {
        Row(Modifier.fillMaxWidth().contentPanel(12).padding(LocalUiDensity.current.contentPadding).testTag("comic-stage"),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SiteImage(video.thumb, site, Modifier.width(80.dp).aspectRatio(.7f).clip(RoundedCornerShape(10.dp)), fit = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Copy(status); Title(video.title, maxLines = 3)
                if (mediaCountOrDuration(video).isNotBlank()) Copy(mediaCountOrDuration(video))
            }
            Action(action, primary = true, icon = actionIcon, modifier = Modifier.widthIn(min = 160.dp)) { play(video) }
        }
    } else if (site.category == SiteCategory.COMIC) {
        Column(Modifier.fillMaxWidth().contentPanel(12).padding(16.dp).testTag("comic-stage"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SiteImage(video.thumb, site, Modifier.width(if (compact) 80.dp else 108.dp).aspectRatio(.7f).clip(RoundedCornerShape(12.dp)), fit = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Copy(status)
                    if (!compact) Title(video.title, maxLines = 3)
                    if (mediaCountOrDuration(video).isNotBlank()) Copy(mediaCountOrDuration(video))
                }
            }
            if (compact) Title(video.title, maxLines = 3)
            Action(action, primary = true, icon = actionIcon, modifier = Modifier.fillMaxWidth()) { play(video) }
        }
    } else if (wide) {
        Row(Modifier.fillMaxWidth().contentPanel(12).testTag(if (site.category == SiteCategory.PHOTO) "photo-stage" else "video-stage"),
            verticalAlignment = Alignment.CenterVertically) {
            SiteImage(video.thumb, site, Modifier.weight(1.1f).height((LocalConfiguration.current.screenHeightDp * .32f).dp.coerceIn(140.dp, 240.dp)), fit = site.category == SiteCategory.PHOTO)
            Column(Modifier.weight(1f).padding(LocalUiDensity.current.sectionGap), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Copy(status); Title(video.title, large = true, maxLines = 3)
                Action(action, primary = true, icon = actionIcon, modifier = Modifier.fillMaxWidth()) { play(video) }
            }
        }
    } else if (!compact) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceColor)
            .testTag(if (site.category == SiteCategory.PHOTO) "photo-stage" else "video-stage")) {
            SiteImage(video.thumb, site, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to Color.Transparent, .35f to Color.Black.copy(alpha = .05f), .65f to Color.Black.copy(alpha = .65f), 1f to SurfaceColor)))
            Column(Modifier.fillMaxWidth().heightIn(min = 340.dp).padding(20.dp),
                verticalArrangement = Arrangement.Bottom) {
                Copy(status)
                Spacer(Modifier.height(6.dp))
                Title(video.title, large = true, maxLines = 3)
                Spacer(Modifier.height(18.dp))
                Action(action, primary = true, icon = actionIcon, modifier = Modifier.fillMaxWidth()) { play(video) }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceColor)
            .testTag(if (site.category == SiteCategory.PHOTO) "photo-stage" else "video-stage")) {
            Box(Modifier.fillMaxWidth().aspectRatio(if (site.category == SiteCategory.PHOTO) 1.5f else 16f / 9f)) {
                SiteImage(video.thumb, site, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, SurfaceColor))))
            }
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Copy(status); Title(video.title, large = !compact, maxLines = 3)
                Action(action, primary = true, icon = actionIcon, modifier = Modifier.fillMaxWidth()) { play(video) }
            }
        }
    }
}
