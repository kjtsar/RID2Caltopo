package org.ncssar.rid2caltopo.video

import kotlin.math.roundToInt

internal fun drawableScaleOrDefault(scale: Float): Float =
    if (scale.isFinite() && scale > 0f) scale else 1.0f

internal fun scaledDimension(value: Int, scale: Float): Int =
    (value * scale).roundToInt().coerceAtLeast(1)
