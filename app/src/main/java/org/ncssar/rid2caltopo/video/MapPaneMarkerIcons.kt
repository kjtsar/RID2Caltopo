package org.ncssar.rid2caltopo.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.util.Locale
import kotlin.math.max

internal fun buildNotamMarkerIcon(
    context: Context,
    fillColor: Int
): Drawable {
    val sizePx = 88
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = sizePx * 0.22f
    val center = sizePx / 2f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.BLACK
        strokeWidth = 6f
    }
    canvas.drawCircle(center, center, radius, fill)
    canvas.drawCircle(center, center, radius, border)
    return BitmapDrawable(context.resources, bitmap)
}

internal fun isKnownArtifactSymbol(symbol: String): Boolean {
    return symbolGlyphForMarkerSymbol(symbol) != null
}

internal fun markerIconForArtifactSymbol(
    resources: android.content.res.Resources,
    symbol: String,
    colorHex: String?,
    cache: MutableMap<String, Drawable>,
    scale: Float = 1.0f
): Drawable {
    val normalizedSymbol = symbol.ifBlank { "point" }
    val normalizedColor = normalizeMarkerColor(colorHex, normalizedSymbol)
    val safeScale = drawableScaleOrDefault(scale)
    val cacheKey = "$normalizedSymbol|$normalizedColor|${"%.3f".format(Locale.US, safeScale)}"
    val cached = cache[cacheKey]
    if (cached != null) {
        return cached.constantState?.newDrawable(resources)?.mutate() ?: cached
    }

    val icon = scaleDrawableBitmap(
        resources = resources,
        drawable = buildCaltopoLikeSymbolDrawable(resources, normalizedSymbol, normalizedColor),
        scale = safeScale
    )
    cache[cacheKey] = icon
    return icon.constantState?.newDrawable(resources)?.mutate() ?: icon
}

internal fun scaleDrawableBitmap(
    resources: android.content.res.Resources,
    drawable: Drawable,
    scale: Float
): Drawable {
    if (scale == 1.0f) return drawable
    val width = scaledDimension(drawable.intrinsicWidth.takeIf { it > 0 } ?: 1, scale)
    val height = scaledDimension(drawable.intrinsicHeight.takeIf { it > 0 } ?: 1, scale)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val previousBounds = Rect(drawable.bounds)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(canvas)
    drawable.setBounds(previousBounds)
    return BitmapDrawable(resources, bitmap)
}

internal fun cachedScaledRemoteMarkerDrawable(
    resources: android.content.res.Resources,
    source: Drawable,
    cache: MutableMap<String, Drawable>,
    cacheKey: String,
    scale: Float
): Drawable {
    val safeScale = drawableScaleOrDefault(scale)
    val scaledCacheKey = "$cacheKey|${"%.3f".format(Locale.US, safeScale)}"
    val cached = cache[scaledCacheKey]
    if (cached != null) {
        return cached.constantState?.newDrawable(resources)?.mutate() ?: cached
    }
    val scaled = scaleDrawableBitmap(
        resources = resources,
        drawable = source.constantState?.newDrawable(resources)?.mutate() ?: source.mutate(),
        scale = safeScale
    )
    cache[scaledCacheKey] = scaled
    return scaled.constantState?.newDrawable(resources)?.mutate() ?: scaled
}

internal fun cachedWhiteOutlinedMarkerDrawable(
    resources: android.content.res.Resources,
    source: Drawable,
    cache: MutableMap<String, Drawable>,
    cacheKey: String,
    outlineWidthPx: Int = 3
): Drawable {
    val safeOutlineWidth = max(1, outlineWidthPx)
    val outlinedCacheKey = "$cacheKey|white-outline|$safeOutlineWidth"
    val cached = cache[outlinedCacheKey]
    if (cached != null) {
        return cached.constantState?.newDrawable(resources)?.mutate() ?: cached
    }

    val sourceWidth = source.intrinsicWidth.takeIf { it > 0 } ?: 1
    val sourceHeight = source.intrinsicHeight.takeIf { it > 0 } ?: 1
    val sourceBitmap = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
    val sourceCanvas = Canvas(sourceBitmap)
    val previousBounds = Rect(source.bounds)
    source.setBounds(0, 0, sourceWidth, sourceHeight)
    source.draw(sourceCanvas)
    source.setBounds(previousBounds)

    val outlinedBitmap = Bitmap.createBitmap(
        sourceWidth + safeOutlineWidth * 2,
        sourceHeight + safeOutlineWidth * 2,
        Bitmap.Config.ARGB_8888
    )
    val outlinedCanvas = Canvas(outlinedBitmap)
    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = PorterDuffColorFilter(AndroidColor.WHITE, PorterDuff.Mode.SRC_IN)
    }
    markerOutlineOffsets(safeOutlineWidth).forEach { (offsetX, offsetY) ->
        outlinedCanvas.drawBitmap(
            sourceBitmap,
            (safeOutlineWidth + offsetX).toFloat(),
            (safeOutlineWidth + offsetY).toFloat(),
            outlinePaint
        )
    }
    outlinedCanvas.drawBitmap(
        sourceBitmap,
        safeOutlineWidth.toFloat(),
        safeOutlineWidth.toFloat(),
        null
    )

    val outlined = BitmapDrawable(resources, outlinedBitmap)
    cache[outlinedCacheKey] = outlined
    return outlined.constantState?.newDrawable(resources)?.mutate() ?: outlined
}

internal fun markerOutlineOffsets(radiusPx: Int): List<Pair<Int, Int>> {
    val safeRadius = max(1, radiusPx)
    return buildList {
        for (y in -safeRadius..safeRadius) {
            for (x in -safeRadius..safeRadius) {
                if (x * x + y * y <= safeRadius * safeRadius) {
                    add(x to y)
                }
            }
        }
    }
}

private fun symbolGlyphForMarkerSymbol(symbol: String): String? {
    return when (symbol.lowercase()) {
        "point" -> "\u2022"
        "c:ring" -> "\u25cb"
        "c:target1" -> "1"
        "c:target2" -> "2"
        "c:target3" -> "3"
        "cp" -> "CP"
        "clue" -> "?"
        "heatsource" -> "HS"
        "fire-hotspot" -> "HOT"
        "medevac-site" -> "+"
        "hut" -> "\u2302"
        "camping" -> "CAMP"
        "radiotower" -> "RT"
        "waterfalls" -> "WF"
        "fuel" -> "F"
        "automobile" -> "CAR"
        "4wd" -> "4W"
        else -> null
    }
}

private fun fallbackGlyphForSymbol(symbol: String): String {
    val compact = symbol.replace("[^A-Za-z0-9]".toRegex(), "").uppercase()
    return when {
        compact.length >= 2 -> compact.substring(0, 2)
        compact.isNotEmpty() -> compact
        else -> "?"
    }
}

private fun normalizeMarkerColor(colorHex: String?, symbol: String): Int {
    val raw = colorHex?.trim().orEmpty()
    if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) {
        return when (symbol.lowercase()) {
            "cp", "clue", "medevac-site" -> AndroidColor.parseColor("#2D4FAE")
            "heatsource", "fire-hotspot", "c:ring", "c:target1", "c:target2", "c:target3", "point" ->
                AndroidColor.parseColor("#FF1B1B")
            else -> AndroidColor.parseColor("#111111")
        }
    }
    val prefixed = if (raw.startsWith("#")) raw else "#$raw"
    return try {
        AndroidColor.parseColor(prefixed)
    } catch (_: IllegalArgumentException) {
        AndroidColor.parseColor("#111111")
    }
}

private fun buildCaltopoLikeSymbolDrawable(
    resources: android.content.res.Resources,
    symbol: String,
    fillColor: Int
): Drawable {
    val sizePx = 56
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = fillColor
        strokeWidth = 4f
    }
    val whiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.WHITE
        strokeWidth = 4f
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.BLACK
    }
    val blackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.BLACK
        strokeWidth = 3f
    }

    when (symbol.lowercase()) {
        "point" -> canvas.drawCircle(cx, cy, 8f, fill)
        "c:ring" -> canvas.drawCircle(cx, cy, 10f, stroke)
        "c:target1" -> {
            canvas.drawCircle(cx, cy, 11f, stroke)
            canvas.drawCircle(cx, cy, 2.5f, fill)
        }
        "c:target2" -> {
            canvas.drawCircle(cx, cy, 11f, stroke)
            canvas.drawLine(cx - 16, cy, cx + 16, cy, stroke)
            canvas.drawLine(cx, cy - 16, cx, cy + 16, stroke)
        }
        "c:target3" -> {
            canvas.drawCircle(cx, cy, 8f, stroke)
            canvas.drawCircle(cx, cy, 14f, stroke)
            canvas.drawLine(cx - 16, cy, cx + 16, cy, stroke)
            canvas.drawLine(cx, cy - 16, cx, cy + 16, stroke)
        }
        "cp" -> {
            val p = Path().apply {
                moveTo(cx - 12, cy - 12)
                lineTo(cx + 12, cy - 12)
                lineTo(cx + 12, cy + 12)
                lineTo(cx - 12, cy + 12)
                close()
            }
            canvas.drawPath(p, fill)
            canvas.drawLine(cx - 10, cy + 10, cx + 10, cy - 10, whiteStroke)
        }
        "clue" -> {
            canvas.drawCircle(cx, cy, 11.5f, fill)
            text.textSize = 22f
            val bounds = Rect()
            text.getTextBounds("?", 0, 1, bounds)
            canvas.drawText("?", cx, cy + bounds.height() / 2f, text)
        }
        "heatsource" -> {
            canvas.drawCircle(cx, cy, 11.5f, stroke)
            canvas.drawLine(cx - 8, cy - 8, cx + 8, cy + 8, stroke)
            canvas.drawLine(cx + 8, cy - 8, cx - 8, cy + 8, stroke)
        }
        "fire-hotspot" -> {
            canvas.drawCircle(cx, cy, 11.5f, stroke)
            canvas.drawCircle(cx, cy, 4.5f, fill)
        }
        "medevac-site" -> {
            stroke.color = AndroidColor.parseColor("#2D4FAE")
            canvas.drawCircle(cx, cy, 11.5f, stroke)
            fill.color = AndroidColor.parseColor("#E61E2B")
            canvas.drawRect(cx - 2, cy - 7, cx + 2, cy + 7, fill)
            canvas.drawRect(cx - 7, cy - 2, cx + 7, cy + 2, fill)
        }
        "hut" -> {
            val roof = Path().apply {
                moveTo(cx - 11, cy + 2)
                lineTo(cx, cy - 10)
                lineTo(cx + 11, cy + 2)
                close()
            }
            canvas.drawPath(roof, black)
            canvas.drawRect(cx - 9, cy + 2, cx + 9, cy + 12, black)
            val door = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE }
            canvas.drawRect(cx - 2, cy + 6, cx + 2, cy + 12, door)
        }
        "camping" -> {
            val tent = Path().apply {
                moveTo(cx - 12, cy + 10)
                lineTo(cx - 1, cy - 10)
                lineTo(cx + 12, cy + 10)
                close()
            }
            canvas.drawPath(tent, blackStroke)
            canvas.drawLine(cx - 2, cy + 10, cx + 3, cy + 2, blackStroke)
        }
        "radiotower" -> {
            canvas.drawLine(cx, cy - 12, cx - 5, cy + 12, stroke)
            canvas.drawLine(cx, cy - 12, cx + 5, cy + 12, stroke)
            canvas.drawLine(cx - 4, cy + 2, cx + 4, cy + 2, stroke)
            canvas.drawLine(cx - 6, cy + 12, cx + 6, cy + 12, stroke)
            canvas.drawArc(cx - 14, cy - 12, cx - 2, cy, -70f, 140f, false, stroke)
            canvas.drawArc(cx + 2, cy - 12, cx + 14, cy, 110f, 140f, false, stroke)
        }
        "waterfalls" -> {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            canvas.drawLine(cx - 8, cy - 10, cx - 8, cy + 6, p)
            canvas.drawLine(cx - 2, cy - 10, cx - 2, cy + 4, p)
            canvas.drawLine(cx + 4, cy - 10, cx + 4, cy + 7, p)
            canvas.drawArc(cx - 12, cy + 2, cx + 10, cy + 16, 200f, 140f, false, p)
        }
        "fuel" -> {
            canvas.drawRect(cx - 8, cy - 10, cx + 4, cy + 10, blackStroke)
            canvas.drawLine(cx + 4, cy - 8, cx + 10, cy - 8, blackStroke)
            canvas.drawLine(cx + 10, cy - 8, cx + 10, cy + 4, blackStroke)
            canvas.drawLine(cx + 10, cy + 4, cx + 6, cy + 4, blackStroke)
            canvas.drawLine(cx - 10, cy + 12, cx + 10, cy + 12, blackStroke)
        }
        "automobile", "4wd" -> {
            val y = cy + 4
            val body = Path().apply {
                moveTo(cx - 12, y)
                lineTo(cx - 7, y - 6)
                lineTo(cx + 5, y - 6)
                lineTo(cx + 12, y)
                lineTo(cx + 12, y + 5)
                lineTo(cx - 12, y + 5)
                close()
            }
            canvas.drawPath(body, black)
            canvas.drawCircle(cx - 7, y + 6, 3f, black)
            canvas.drawCircle(cx + 7, y + 6, 3f, black)
            if (symbol.lowercase() == "4wd") {
                canvas.drawRect(cx - 2, y - 12, cx + 2, y - 6, black)
            }
        }
        else -> {
            val glyph = symbolGlyphForMarkerSymbol(symbol) ?: fallbackGlyphForSymbol(symbol)
            canvas.drawCircle(cx, cy, sizePx * 0.38f, fill)
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = AndroidColor.WHITE
                strokeWidth = 3f
            }
            canvas.drawCircle(cx, cy, sizePx * 0.38f, border)
            text.textSize = if (glyph.length > 2) 14f else 18f
            val bounds = Rect()
            text.getTextBounds(glyph, 0, glyph.length, bounds)
            canvas.drawText(glyph, cx, cy + bounds.height() / 2f, text)
        }
    }

    return BitmapDrawable(resources, bitmap)
}
