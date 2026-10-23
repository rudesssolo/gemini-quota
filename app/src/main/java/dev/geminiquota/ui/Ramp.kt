package dev.geminiquota.ui

/**
 * Ease-out interpolation shared by the app and the widget ramp.
 *
 * Kept here, free of Android types, so the curve can be unit-tested: the widget
 * cannot use a `ValueAnimator`, it fakes the motion by publishing a handful of
 * partial updates, and those frames need the same curve as the app.
 */
object Ramp {

    /** Cubic ease-out: fast start, gentle settle. */
    fun ease(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        val inverse = 1f - f
        return 1f - inverse * inverse * inverse
    }

    /** Eased value between [from] and [to]; the endpoints are exact. */
    fun valueAt(from: Int, to: Int, fraction: Float): Int {
        if (from == to) return to
        if (fraction <= 0f) return from
        if (fraction >= 1f) return to
        return Math.round(from + (to - from) * ease(fraction))
    }
}
