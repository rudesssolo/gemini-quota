package dev.geminiquota.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Animates a quota bar and its percentage as one motion.
 *
 * The number counts up with the bar and the colour cross-fades when the severity
 * changes, so a refresh reads as a transition rather than a jump cut. The running
 * animator is parked in the bar's tag: a render that arrives mid-flight cancels
 * the old run and retargets from wherever the bar currently is, which keeps
 * rapid refreshes from snapping backwards.
 */
object QuotaProgressAnimator {

    private const val DURATION_MS = 620L
    private const val POP_SCALE = 1.05f
    private const val POP_MS = 280L

    fun animate(
        progressBar: ProgressBar,
        percentText: TextView,
        targetPercent: Int,
        targetColor: Int,
        format: (Int) -> String,
    ) {
        val target = targetPercent.coerceIn(0, 100)
        val from = progressBar.progress.coerceIn(0, 100)
        val fromColor = percentText.currentTextColor

        cancel(progressBar)

        if (from == target) {
            progressBar.progress = target
            percentText.text = format(target)
            percentText.setTextColor(targetColor)
            return
        }

        pop(percentText)

        val argb = ArgbEvaluator()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { value ->
                val fraction = value.animatedFraction
                val current = from + (target - from) * Ramp.ease(fraction)
                progressBar.progress = current.roundToInt()
                percentText.text = format(current.roundToInt())
                percentText.setTextColor(argb.evaluate(fraction, fromColor, targetColor) as Int)
            }
        }
        var cancelled = false
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                progressBar.progress = target
                percentText.text = format(target)
                percentText.setTextColor(targetColor)
                if (progressBar.tag === animator) progressBar.tag = null
            }
        })

        progressBar.tag = animator
        animator.start()
    }

    /** Stops a running transition, e.g. when the window disappears entirely. */
    fun cancel(progressBar: ProgressBar) {
        (progressBar.tag as? ValueAnimator)?.let {
            progressBar.tag = null
            it.cancel()
        }
    }

    /** A short "breath" on the number so the change registers at a glance. */
    private fun pop(percentText: TextView) {
        percentText.animate().cancel()
        percentText.scaleX = POP_SCALE
        percentText.scaleY = POP_SCALE
        percentText.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(POP_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
