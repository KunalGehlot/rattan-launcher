package site.gehlot.rattan

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How the lens looks over a focusable view, set as that view's tag. Within one [group] the lens
 * glides from item to item; between groups it fades out and appears at the new place.
 * A [ring] lens circles content (app banners) without touching it; otherwise it is a pane of
 * light glass over a control.
 */
class LensSpot(val group: String, val radius: Float, val ring: Boolean, val caption: String)

/**
 * The one focus indicator on the home screen: a piece of glass that springs to whatever has focus,
 * changing its size and corners on the way. It is a small view moved without layout passes, so
 * holding a direction key keeps it gliding, retargeted from where it is, never restarted.
 */
class FocusLens(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val margin = 16 * density          // room around the target for the ring and its glow
    private val now = FloatArray(5)            // left, top, right, bottom, corner radius
    private val speed = FloatArray(5)
    private val goal = FloatArray(5)
    private val location = IntArray(2)
    private val box = RectF()
    private val scratch = RectF()
    private val unit = Matrix()
    private var spot: LensSpot? = null
    private var target: View? = null
    private var running = false
    private var lastFrame = 0L

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = 0xF5FFF7EE.toInt()
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        color = GOLD
    }
    // The control lens sits on top of its label, so it stays mostly clear: rim and a sheen up top.
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0CFFFFFF }
    private val sheenShader = LinearGradient(0f, 0f, 0f, 0.42f, 0x2AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sheenShader }
    private val rimShader = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(0xF0FFFFFF.toInt(), 0x30FFFFFF, 0x80FFFFFF.toInt()),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
        shader = rimShader
    }

    private val tick = Choreographer.FrameCallback { step(it) }

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // No elevation: the lens is clear, so a system shadow would show through it and grey its label.
        outlineProvider = null
    }

    /** Layout passes leave the lens where its spring put it. */
    override fun layout(l: Int, t: Int, r: Int, b: Int) = super.layout(left, top, right, bottom)

    fun follow(target: View?, glide: Boolean) {
        val next = target?.tag as? LensSpot
        if (target == null || next == null) {
            spot = null
            this.target = null
            animate().alpha(0f).setDuration(120).start()
            return
        }
        if (target.width == 0) {
            // Not laid out yet. By the time it is, focus may have moved on, so follow whatever has it then.
            post { follow(rootView.findFocus(), glide = false) }
            return
        }
        this.target = target
        aimAt(target, next)
        spot = next

        if (glide && alpha > 0.5f) {
            if (!running) {
                running = true
                lastFrame = 0L
                Choreographer.getInstance().postFrameCallback(tick)
            }
        } else {
            // A new group: appear in place, a touch smaller first, instead of flying across the screen.
            running = false
            Choreographer.getInstance().removeFrameCallback(tick)
            goal.copyInto(now)
            speed.fill(0f)
            place()
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(EASE).start()
        }
    }

    /**
     * After a layout pass the focused view may have moved (the dock grows as apps load), or focus may
     * have changed without the lens hearing of it: re-aim at whatever has focus now.
     */
    fun refresh() {
        val focused = rootView.findFocus()
        if (focused !== target) {
            follow(focused, glide = false)
            return
        }
        val current = target ?: return
        val currentSpot = spot ?: return
        if (current.width == 0) return
        val before = goal.copyOf()
        aimAt(current, currentSpot)
        if (before.contentEquals(goal) || running) return
        goal.copyInto(now)
        speed.fill(0f)
        place()
    }

    private fun aimAt(view: View, at: LensSpot) {
        view.getLocationInWindow(location)
        goal[0] = location[0].toFloat()
        goal[1] = location[1].toFloat()
        goal[2] = goal[0] + view.width
        goal[3] = goal[1] + view.height
        goal[4] = at.radius
    }

    private fun step(frameNanos: Long) {
        if (!running) return
        val dt = if (lastFrame == 0L) 1 / 60f else ((frameNanos - lastFrame) / 1e9f).coerceIn(0f, 1 / 20f)
        lastFrame = frameNanos
        var remaining = dt
        while (remaining > 0f) {
            val h = min(remaining, 1 / 120f)
            for (i in 0 until 5) {
                val pull = STIFFNESS * (goal[i] - now[i]) - DAMPING * speed[i]
                speed[i] += pull * h
                now[i] += speed[i] * h
            }
            remaining -= h
        }
        var settled = true
        for (i in 0 until 5) if (abs(goal[i] - now[i]) > 0.4f || abs(speed[i]) > 8f) settled = false
        if (settled) {
            goal.copyInto(now)
            speed.fill(0f)
            running = false
        } else {
            Choreographer.getInstance().postFrameCallback(tick)
        }
        place()
    }

    /**
     * Moves the view without a layout pass; fractions of a pixel go into the drawing. The whole
     * screen is repainted, not just the lens: on this TV, repainting only the changed area leaves
     * pieces of the old lens behind and cuts off the new one while it moves.
     */
    private fun place() {
        val l = floor(now[0] - margin).toInt()
        val t = floor(now[1] - margin).toInt()
        val r = ceil(now[2] + margin).toInt()
        val b = ceil(now[3] + margin).toInt()
        setLeftTopRightBottom(l, t, r, b)
        box.set(now[0] - l, now[1] - t, now[2] - l, now[3] - t)
        invalidate()
        (parent as? View)?.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val current = spot ?: return
        val r = now[4]
        if (current.ring) {
            // A soft gold glow outside, then a crisp ring; the banner itself is left untouched.
            for ((i, a) in GLOW.withIndex()) {
                val o = (3.5f + 2.3f * (i + 1)) * density
                glowPaint.alpha = a
                scratch.set(box.left - o, box.top - o, box.right + o, box.bottom + o)
                canvas.drawRoundRect(scratch, r + o, r + o, glowPaint)
            }
            val o = 3.5f * density
            scratch.set(box.left - o, box.top - o, box.right + o, box.bottom + o)
            canvas.drawRoundRect(scratch, r + o, r + o, ringPaint)
        } else {
            unit.setScale(1f, box.height())
            unit.postTranslate(0f, box.top)
            sheenShader.setLocalMatrix(unit)
            rimShader.setLocalMatrix(unit)
            canvas.drawRoundRect(box, r, r, fill)
            canvas.drawRoundRect(box, r, r, sheen)
            val half = rim.strokeWidth / 2
            scratch.set(box.left + half, box.top + half, box.right - half, box.bottom - half)
            canvas.drawRoundRect(scratch, r, r, rim)
        }
    }

    private companion object {
        const val GOLD = 0xFFE2B56F.toInt()   // the wallpaper's own gold
        val GLOW = intArrayOf(70, 38, 18, 7)
        const val STIFFNESS = 420f
        val DAMPING = 2 * 0.74f * sqrt(STIFFNESS)   // a little give at the end, no wobble
        val EASE = DecelerateInterpolator(2f)
    }
}
