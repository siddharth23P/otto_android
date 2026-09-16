package dev.otto.phone.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.otto.phone.ui.theme.OttoTheme
import dev.otto.phone.ui.theme.OttoType

/** The DataNodes square mark in clay: a rounded square, an outlined inner square and a bar across
 *  it, both in the background colour (LandingView.vue .mark). 26 dp, or 16 dp small. */
@Composable
fun OttoMark(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val colors = OttoTheme.colors
    val small = size < 20.dp
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val unit = s / (if (small) 16f else 26f)
        drawRoundRect(colors.accent, cornerRadius = CornerRadius(8.dp.toPx().coerceAtMost(s / 3)))
        val inner = (if (small) 6f else 11f) * unit
        val stroke = (if (small) 1.2f else 1.6f) * unit
        val corner = CornerRadius((if (small) 2f else 3f) * unit)
        drawRoundRect(
            colors.bg, topLeft = Offset((s - inner) / 2 + stroke / 2, (s - inner) / 2 + stroke / 2),
            size = Size(inner - stroke, inner - stroke), cornerRadius = corner, style = Stroke(stroke),
        )
        val bar = (if (small) 9f else 16f) * unit
        drawRect(colors.bg, topLeft = Offset((s - bar) / 2, (s - stroke) / 2), size = Size(bar, stroke))
    }
}

/** "OTTO" in the DataNodes wordmark style, beside the mark. Read by TalkBack as "Otto". */
@Composable
fun Wordmark(modifier: Modifier = Modifier, markSize: Dp = 22.dp) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = "Otto"; heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OttoMark(size = markSize)
        Text(OttoType.caps("otto"), style = OttoTheme.type.wordmark)
    }
}
