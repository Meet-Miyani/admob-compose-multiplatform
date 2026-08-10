package dev.avinya.admob.showcase.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import dev.avinya.admob.showcase.ui.theme.sectionColor
import dev.avinya.admob.showcase.ui.theme.showcaseColors

/**
 * Deterministic cover artwork for an article.
 *
 * The seed corpus has no images, and a reading app without them looks broken.
 * Rather than ship binaries for every seeded article — which would have to grow
 * with the seed data — each cover is drawn from the article's own id: the
 * section picks the hue, and the id picks the motif and its geometry. The same
 * article therefore always looks the same, on both platforms, offline, with no
 * asset loading and no new dependency.
 *
 * It also earns its place in an ads showcase: a native ad's `media` view renders
 * a real photograph or video right next to these, so the difference between
 * editorial content and sponsored content stays visible at a glance.
 */
@Composable
fun ArticleCover(
    articleId: String,
    section: String,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
    monogram: Boolean = true,
) {
    val hue = sectionColor(section)
    val palette = showcaseColors
    val seed = remember(articleId) { stableSeed(articleId) }
    val measurer = rememberTextMeasurer()

    val base = if (palette.isDark) lerp(hue, Color.Black, 0.62f) else lerp(hue, Color.White, 0.10f)
    val top = if (palette.isDark) lerp(hue, Color.Black, 0.45f) else lerp(hue, Color.White, 0.32f)
    val figure = if (palette.isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.22f)
    val figureStrong = if (palette.isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.34f)

    Canvas(
        modifier = modifier
            .aspectRatio(aspectRatio)
            // Decorative: it repeats the section label that already sits beside
            // it, so announcing it would just be noise.
            .clearAndSetSemantics { },
    ) {
        drawRect(brush = Brush.linearGradient(listOf(top, base)))

        when (seed % 4) {
            0 -> drawArcs(seed, figure, figureStrong)
            1 -> drawBands(seed, figure, figureStrong)
            2 -> drawDots(seed, figure)
            else -> drawBars(seed, figure, figureStrong)
        }

        if (monogram && section.isNotBlank()) {
            val glyph = section.trim().take(1).uppercase()
            val style = TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = (size.minDimension * 0.42f).toSp(),
                color = Color.White.copy(alpha = if (palette.isDark) 0.14f else 0.26f),
            )
            val laid = measurer.measure(glyph, style)
            drawText(
                textLayoutResult = laid,
                topLeft = Offset(
                    x = size.width - laid.size.width - size.width * 0.06f,
                    y = size.height - laid.size.height + size.height * 0.06f,
                ),
            )
        }
    }
}

private fun DrawScope.drawArcs(seed: Int, figure: Color, figureStrong: Color) {
    val origin = Offset(size.width * 0.14f, size.height * 1.05f)
    val step = size.minDimension * 0.30f
    val stroke = Stroke(width = size.minDimension * 0.055f)
    repeat(4) { index ->
        val radius = step * (index + 1) + (seed % 7) * 1.5f
        drawCircle(
            color = if (index % 2 == 0) figureStrong else figure,
            radius = radius,
            center = origin,
            style = stroke,
        )
    }
}

private fun DrawScope.drawBands(seed: Int, figure: Color, figureStrong: Color) {
    val count = 5 + seed % 3
    val width = size.minDimension * 0.09f
    val spacing = size.width / count
    repeat(count) { index ->
        val x = spacing * index - size.height * 0.5f + (seed % 11)
        drawLine(
            color = if (index % 2 == 0) figure else figureStrong,
            start = Offset(x, size.height),
            end = Offset(x + size.height, 0f),
            strokeWidth = width,
        )
    }
}

private fun DrawScope.drawDots(seed: Int, figure: Color) {
    val columns = 7 + seed % 4
    val rows = (columns * size.height / size.width).toInt().coerceAtLeast(3)
    val radius = size.minDimension * 0.028f
    val dx = size.width / (columns + 1)
    val dy = size.height / (rows + 1)
    for (column in 1..columns) {
        for (row in 1..rows) {
            // Fade the field toward the top-right so the monogram stays legible.
            val fade = 1f - (column.toFloat() / columns) * 0.55f
            drawCircle(
                color = figure.copy(alpha = figure.alpha * fade),
                radius = radius,
                center = Offset(dx * column, dy * row),
            )
        }
    }
}

private fun DrawScope.drawBars(seed: Int, figure: Color, figureStrong: Color) {
    val count = 4 + seed % 3
    val height = size.height / (count * 2f)
    repeat(count) { index ->
        val fraction = 0.35f + ((seed shr index) % 6) * 0.11f
        drawRect(
            color = if (index % 2 == 0) figureStrong else figure,
            topLeft = Offset(size.width * 0.08f, height * (index * 2 + 0.6f)),
            size = Size(size.width * fraction.coerceAtMost(0.84f), height),
        )
    }
}

/**
 * A stable, non-negative seed for [key].
 *
 * `String.hashCode` is specified by Kotlin, so this is identical on Android and
 * iOS and across process restarts — which is the whole contract of a cover that
 * must not change between launches. Masking beats `abs`, which returns a
 * negative number for `Int.MIN_VALUE` and would flip every `%` below.
 */
private fun stableSeed(key: String): Int = key.hashCode() and Int.MAX_VALUE
