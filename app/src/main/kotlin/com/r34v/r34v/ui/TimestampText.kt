package com.r34v.r34v.ui

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.r34v.r34v.data.timestampSpans

@Composable fun TimestampText(text: String, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val spans = timestampSpans(text, durationMs)
    val annotated = buildAnnotatedString {
        append(text)
        spans.forEach { span ->
            addStyle(SpanStyle(color = BrandColor, textDecoration = TextDecoration.Underline), span.start, span.end)
            addStringAnnotation("timestamp", span.positionMs.toString(), span.start, span.end)
        }
    }
    ClickableText(annotated, modifier, style = MaterialTheme.typography.bodySmall.copy(color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, lineHeight = 18.sp), onClick = { offset ->
        annotated.getStringAnnotations("timestamp", offset, offset).firstOrNull()?.item?.toLongOrNull()?.let(onSeek)
    })
}
