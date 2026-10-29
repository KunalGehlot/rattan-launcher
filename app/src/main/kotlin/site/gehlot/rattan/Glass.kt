package site.gehlot.rattan

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/** A rounded-rectangle outline, for shadows and clipping. */
fun roundOutline(radius: Float) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
}

/**
 * Marks where a glass panel goes. It draws nothing itself: the stage behind the views renders the
 * glass at this view's position, with this corner radius.
 */
class GlassLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var cornerRadius: Float = 0f

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.GlassLayout)
        cornerRadius = a.getDimension(R.styleable.GlassLayout_cornerRadius, 0f)
        a.recycle()
    }
}
