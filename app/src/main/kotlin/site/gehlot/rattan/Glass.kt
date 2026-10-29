package site.gehlot.rattan

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/** The blurred copy of the wallpaper; every glass surface shows the slice that lies behind it. */
object Backdrop {
    lateinit var blur: Bitmap
        private set

    fun load(res: Resources) {
        if (::blur.isInitialized) return
        blur = BitmapFactory.decodeResource(res, R.drawable.wallpaper_blur, BitmapFactory.Options().apply { inScaled = false })
    }
}

/** A rounded-rectangle outline, for shadows and clipping. */
fun roundOutline(radius: Float) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
}

/**
 * A floating glass surface: the blurred wallpaper behind it, a light tint, a sheen from above and a
 * bright rim. There is no live blur, because the wallpaper never changes. Brightens when focused.
 */
class GlassLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    var cornerRadius: Float = 0f
        set(value) {
            field = value
            outlineProvider = roundOutline(value)
            invalidate()
        }

    private val backdrop = BitmapShader(Backdrop.blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = backdrop }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()
    private val mapping = Matrix()
    private val location = IntArray(2)

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.GlassLayout)
        cornerRadius = a.getDimension(R.styleable.GlassLayout_cornerRadius, 0f)
        a.recycle()
        setWillNotDraw(false)
        val density = resources.displayMetrics.density
        rim.strokeWidth = 1.2f * density
        elevation = 6 * density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val half = rim.strokeWidth / 2
        box.set(half, half, w - half, h - half)
        sheen.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x8CFFFFFF.toInt(), 0x14FFFFFF, 0x3DFFFFFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        // Stretch the small blurred wallpaper over the screen, then shift it to this view's position.
        val root = rootView
        getLocationInWindow(location)
        mapping.setScale(root.width.toFloat() / Backdrop.blur.width, root.height.toFloat() / Backdrop.blur.height)
        mapping.postTranslate(-location[0].toFloat(), -location[1].toFloat())
        backdrop.setLocalMatrix(mapping)

        val r = cornerRadius
        glass.colorFilter = if (isFocused) null else DIM
        tint.color = if (isFocused) 0x47FFFFFF else 0x10FFFFFF
        rim.alpha = if (isFocused) 255 else 190
        canvas.drawRoundRect(box, r, r, glass)
        canvas.drawRoundRect(box, r, r, tint)
        canvas.drawRoundRect(box, r, r, sheen)
        canvas.drawRoundRect(box, r, r, rim)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    private companion object {
        /** Darkens the glass a little so light text on it stays easy to read. */
        val DIM = LightingColorFilter(0xD0D0D0, 0)
    }
}

/** The rim and sheen a focused app tile wears, drawn over its banner like light catching glass. */
class FocusRim(private val radius: Float, density: Float) : Drawable() {
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun onBoundsChange(bounds: Rect) {
        val half = rim.strokeWidth / 2
        box.set(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half)
        rim.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            intArrayOf(0xEBFFFFFF.toInt(), 0x47FFFFFF, 0x8CFFFFFF.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        // A soft diagonal highlight over the upper left, as on curved glass.
        sheen.shader = LinearGradient(
            bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.left + bounds.width() * 0.55f, bounds.top + bounds.height() * 0.9f,
            0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(box, radius, radius, sheen)
        canvas.drawRoundRect(box, radius, radius, rim)
    }

    override fun setAlpha(alpha: Int) {
        rim.alpha = alpha
        sheen.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
