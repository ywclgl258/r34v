package com.r34v.r34v.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.r34v.r34v.data.timestampSpans

@Composable fun TimestampText(text: String, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val spans = timestampSpans(text, durationMs)
    val colors = MaterialTheme.colorScheme
    val annotated = buildAnnotatedString {
        append(text)
        spans.forEach { span ->
            addStyle(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline), span.start, span.end)
            addLink(LinkAnnotation.Clickable("timestamp:${span.positionMs}", linkInteractionListener = { onSeek(span.positionMs) }), span.start, span.end)
        }
    }
    Text(annotated, modifier, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
}
