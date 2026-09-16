package io.github.puvon.enetrend.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DashboardDetails(day: DashboardChartDay) {
    Text(day.date.toString(), style = MaterialTheme.typography.titleMedium)
    day.details().forEach { detail ->
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            stateDescription = detail.statuses.joinToString("・") { it.label }
        }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val missing = detail.statuses.firstOrNull {
                it == DetailStatus.MISSING || it == DetailStatus.UNAVAILABLE
            }
            val iconAt = detail.text.indexOf('（').takeIf { it >= 0 } ?: detail.text.length
            Text(buildAnnotatedString {
                append(detail.text.substring(0, iconAt))
                appendInlineContent("status")
                append(detail.text.substring(iconAt))
            }, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(detail.text) },
                inlineContent = mapOf("status" to InlineTextContent(
                    Placeholder(24.sp, 20.sp, PlaceholderVerticalAlign.Center)
                ) { DetailStatusIcon(missing ?: DetailStatus.AVAILABLE) }))
            // Vertical badges remain readable at large font sizes and narrow widths.
            detail.statuses.filter { it !in listOf(DetailStatus.AVAILABLE, DetailStatus.MISSING, DetailStatus.UNAVAILABLE) }.forEach { status ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailStatusIcon(status)
                    Text(status.label, style = MaterialTheme.typography.labelMedium)
                }
            }
            detail.notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Decorative icon: the adjacent text and row semantics convey its meaning without relying on color. */
@Composable
private fun DetailStatusIcon(status: DetailStatus) {
    val color = when (status) {
        DetailStatus.AVAILABLE -> if (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
            Color(0xFF81C784) else Color(0xFF2E7D32)
        DetailStatus.MISSING, DetailStatus.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.tertiary
    }
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.8.dp.toPx()
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        when (status) {
            DetailStatus.AVAILABLE -> {
                drawCircle(color, size.minDimension * 0.43f, style = Stroke(stroke))
                drawLine(color, point(0.25f, 0.5f), point(0.43f, 0.68f), stroke)
                drawLine(color, point(0.43f, 0.68f), point(0.76f, 0.32f), stroke)
            }
            DetailStatus.MISSING, DetailStatus.UNAVAILABLE -> {
                drawCircle(color, size.minDimension * 0.43f, style = Stroke(stroke))
                drawLine(color, point(0.28f, 0.5f), point(0.72f, 0.5f), stroke)
            }
            else -> {
                val triangle = Path().apply {
                    moveTo(size.width * 0.5f, size.height * 0.08f)
                    lineTo(size.width * 0.94f, size.height * 0.88f)
                    lineTo(size.width * 0.06f, size.height * 0.88f)
                    close()
                }
                drawPath(triangle, color, style = Stroke(stroke))
                drawLine(color, point(0.5f, 0.36f), point(0.5f, 0.58f), stroke)
                drawCircle(color, stroke / 2, point(0.5f, 0.73f))
            }
        }
    }
}
