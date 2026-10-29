package site.gehlot.rattan

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/** A rounded-rectangle outline, for shadows and clipping, optionally inset from the view's edges. */
fun roundOutline(radius: Float, inset: Int = 0) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) =
        outline.setRoundRect(inset, inset, view.width - inset, view.height - inset, radius)
}

/**
 * Marks where a glass panel goes. It draws nothing itself: the stage behind the views renders the
 * glass at this view's position, with this corner radius, brighter while it has focus.
 */
class GlassLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var cornerRadius: Float = 0f

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.GlassLayout)
        cornerRadius = a.getDimension(R.styleable.GlassLayout_cornerRadius, 0f)
        a.recycle()
    }
}

/** A crisp white ring just outside a focused tile, its corners following the tile's own. */
class FocusRing(private val radius: Float, density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = 0xF2FFFFFF.toInt()
    }
    private val box = RectF()

    override fun onBoundsChange(bounds: Rect) {
        val half = paint.strokeWidth / 2
        box.set(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half)
    }

    override fun draw(canvas: Canvas) = canvas.drawRoundRect(box, radius, radius, paint)

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
