package com.r34v.r34v.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R
import com.r34v.r34v.data.*

fun mediaCountOrDuration(video: Video): String = video.duration.takeUnless {
    (video.mediaKind == MediaKind.GALLERY || SiteRegistry.byIdOrDefault(video.site).mediaKind == MediaKind.GALLERY) &&
        it.trim().matches(Regex("\\d{1,3}:\\d{2}(:\\d{2})?"))
}.orEmpty()

@Composable internal fun GalleryResumeCard(video: Video, action: String, status: String, play: () -> Unit) {
    val site = SiteRegistry.byIdOrDefault(video.site)
    Row(Modifier.fillMaxWidth().clip(ArtworkShape).background(MaterialTheme.colorScheme.surface)
        .clickable(role = Role.Button, onClickLabel = action, onClick = play).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        SiteImage(video.thumb, site, Modifier.size(46.dp, 60.dp).clip(RoundedCornerShape(7.dp)), fit = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(action, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        Glyph(R.drawable.ic_chevron_right, null, Modifier.size(16.dp))
    }
}
