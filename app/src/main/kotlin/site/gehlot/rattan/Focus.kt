package site.gehlot.rattan

import android.view.View
import android.view.animation.DecelerateInterpolator

/** Focus is one short, smooth step up in size and height, and nothing that repeats or pulses. */
object Focus {
    private val ease = DecelerateInterpolator(1.6f)

    fun lift(view: View, scale: Float, onChange: (Boolean) -> Unit = {}) {
        val raise = 10 * view.resources.displayMetrics.density
        view.setOnFocusChangeListener { v, focused ->
            onChange(focused)
            v.animate()
                .scaleX(if (focused) scale else 1f)
                .scaleY(if (focused) scale else 1f)
                .translationZ(if (focused) raise else 0f)
                .setDuration(160)
                .setInterpolator(ease)
                .start()
        }
    }
}
