package site.gehlot.rattan

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.dreams.DreamService
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.time.LocalTime
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The screensaver: public-domain paintings, framed and matted the way a gallery hangs them. Every
 * picture arrives finished from tools/art/render.py (moulding, mat, bevel and shadows drawn in), so
 * the TV decodes one JPEG at a time and cross-fades to it; between fades nothing is redrawn.
 */
class ArtDream : DreamService() {
    private var gallery: Gallery? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false // any key wakes the TV, as with every screensaver
        isFullscreen = true
        Type.load(assets)
        gallery = Gallery(this).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        gallery?.start()
    }

    override fun onDreamingStopped() {
        gallery?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        gallery?.release()
        gallery = null
        super.onDetachedFromWindow()
    }
}

private class Work(
    val id: String,
    val file: File,
    val title: String,
    val artist: String,
    val date: String,
    val museum: String,
    val rect: Rect,
    val ink: Int,
)

/** The catalog written by render.py and pushed to the TV by tools/art/push.sh. */
private object Catalog {
    fun dir(context: Context): File? = context.getExternalFilesDir("art")

    fun load(context: Context): List<Work> {
        val dir = dir(context) ?: return emptyList()
        return try {
            val works = JSONObject(File(dir, "catalog.json").readText()).getJSONArray("works")
            (0 until works.length()).map { works.getJSONObject(it) }.map { w ->
                val r = w.getJSONArray("rect")
                Work(
                    id = w.getString("id"),
                    file = File(dir, w.getString("file")),
                    title = w.getString("title"),
                    artist = w.getString("artist"),
                    date = w.getString("date"),
                    museum = w.getString("museum"),
                    rect = Rect(r.getInt(0), r.getInt(1), r.getInt(0) + r.getInt(2), r.getInt(1) + r.getInt(3)),
                    ink = Color.parseColor(w.getString("ink")),
                )
            }.filter { it.file.isFile }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * A shuffled order that plays to the end before it reshuffles, kept across screensaver runs. The
 * same artist never hangs twice in a row, and a picture counts as seen only once it was shown.
 */
private object Playlist {
    fun upcoming(context: Context, works: List<Work>): Work {
        val prefs = prefs(context)
        val ids = works.map { it.id }
        val saved = prefs.getString("order", null)?.split('\n')?.takeIf { it.toSet() == ids.toSet() }
        val next = prefs.getInt("next", 0)
        if (saved != null && next < saved.size) return works.first { it.id == saved[next] }
        val byId = works.associateBy { it.id }
        val order = varied(works.shuffled(), saved?.lastOrNull()?.let { byId[it]?.artist })
        prefs.edit().putString("order", order.joinToString("\n") { it.id }).putInt("next", 0).apply()
        return order.first()
    }

    fun advance(context: Context) = prefs(context).let { it.edit().putInt("next", it.getInt("next", 0) + 1).apply() }

    private fun varied(pool: List<Work>, lastArtist: String?): List<Work> {
        val left = pool.toMutableList()
        val order = ArrayList<Work>(pool.size)
        var previous = lastArtist
        while (left.isNotEmpty()) {
            val pick = left.indexOfFirst { it.artist != previous }.coerceAtLeast(0)
            order += left.removeAt(pick)
            previous = order.last().artist
        }
        return order
    }

    private fun prefs(context: Context) = context.getSharedPreferences("art", Context.MODE_PRIVATE)
}

/**
 * The catalog as a slow sequence of finished pictures. The screensaver only plays it; Art Mode
 * ([ArtActivity]) also steps through it with the remote and shows the label on OK.
 */
class Gallery(context: Context) : FrameLayout(context) {
    private val art = ArtView(context)
    private val label = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; alpha = 0f }
    private val title = text(Type.displayItalic, 27f)
    private val byline = text(Type.text, 14f)
    private val museum = text(Type.text, 12.5f)
    private val dim = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private val hideLabel = Runnable { label.animate().alpha(0f).setStartDelay(0).setDuration(real(LABEL_FADE_MS * 2)).start() }
    private var works = emptyList<Work>()
    private val history = ArrayList<Work>()
    private var at = -1        // the picture on screen, as a place in [history]
    private var generation = 0 // bumped by every step, so a decode that finishes late is dropped
    private var running = false
    private var failures = 0

    init {
        setBackgroundColor(Color.BLACK)
        addView(art, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        for (line in listOf(title, byline, museum)) label.addView(line, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(label, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(dim, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun start() {
        running = true
        works = Catalog.load(context)
        if (works.isEmpty()) {
            addView(text(Type.text, 16f).apply {
                text = context.getString(R.string.art_missing)
                setTextColor(0x99FFFFFF.toInt())
                gravity = Gravity.CENTER
            }, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            return
        }
        queue(0, FADE_MS)
    }

    fun stop() {
        running = false
        generation++ // a decode still running when Art Mode stops must not land after a restart
        main.removeCallbacksAndMessages(null)
    }

    fun release() {
        stop()
        decoder.shutdownNow()
        art.clear()
    }

    /** Art Mode: the next or the previous picture right away, with a quicker fade. */
    fun step(direction: Int) {
        if (!running || works.isEmpty() || at < 0) return
        val target = at + direction
        if (target < 0) return
        generation++
        main.removeCallbacksAndMessages(null)
        if (target <= history.lastIndex) show(history[target], target, 0, STEP_FADE_MS) else queue(0, STEP_FADE_MS)
    }

    /** Art Mode's OK: the wall label in, or out if it is showing. */
    fun toggleLabel() {
        val work = history.getOrNull(at) ?: return
        main.removeCallbacks(hideLabel)
        if (label.alpha > 0.5f) {
            hideLabel.run()
        } else {
            place(work)
            label.animate().alpha(1f).setStartDelay(0).setDuration(real(LABEL_FADE_MS)).start()
            main.postDelayed(hideLabel, LABEL_FADE_MS + LABEL_SHOWN_MS)
        }
    }

    /** The next picture of the playlist. */
    private fun queue(delay: Long, fade: Long) {
        if (!running) return
        show(Playlist.upcoming(context, works), null, delay, fade)
    }

    /**
     * Decodes [work] shortly before it is due, so the fade starts on time, then fades to it.
     * [index] is its place in the history when stepping back to it; null for a new picture.
     */
    private fun show(work: Work, index: Int?, delay: Long, fade: Long) {
        val due = SystemClock.uptimeMillis() + delay
        val ticket = generation
        main.postDelayed({
            decoder.execute {
                val bitmap = try {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(work.file)) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE // straight to the GPU, no heap copy
                    }
                } catch (_: Exception) {
                    null
                }
                main.postAtTime({ arrive(work, bitmap, index, fade, ticket) }, due)
            }
        }, (delay - PREFETCH_MS).coerceAtLeast(0))
    }

    private fun arrive(work: Work, bitmap: Bitmap?, index: Int?, fade: Long, ticket: Int) {
        if (!running || ticket != generation) return
        if (index == null) Playlist.advance(context)
        if (bitmap == null) {
            if (++failures < works.size) queue(0, fade)
            return
        }
        failures = 0
        if (index == null) {
            history += work
            if (history.size > HISTORY) history.removeAt(0)
            at = history.lastIndex
        } else {
            at = index
        }
        present(work, bitmap, fade)
        queue(DWELL_MS, FADE_MS)
    }

    private fun present(work: Work, bitmap: Bitmap, fade: Long) {
        main.removeCallbacks(hideLabel)
        label.animate().cancel()
        label.alpha = 0f
        dim.animate().alpha(nightDim()).setDuration(real(fade)).start()
        art.fadeTo(bitmap, real(fade)) {
            place(work)
            label.animate().alpha(1f).setStartDelay(real(LABEL_DELAY_MS)).setDuration(real(LABEL_FADE_MS)).start()
            main.postDelayed(hideLabel, LABEL_DELAY_MS + LABEL_FADE_MS + LABEL_SHOWN_MS)
        }
    }

    /**
     * Animators run at the system's animator duration scale (0.5 on this TV); the gallery keeps its
     * own pace, so its fades and the label's time on screen are the same whatever that setting is.
     */
    private fun real(ms: Long): Long = (ms / pace).toLong()

    private val pace = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        .takeIf { it > 0f } ?: 1f

    /**
     * A wall label: beside the painting when the mat leaves room on the right, as in a museum,
     * otherwise right-aligned beneath it. Positions are in the 1920 x 1080 pixels render.py drew.
     */
    private fun place(work: Work) {
        val scale = width / 1920f
        val r = work.rect
        val beside = r.left - FRAME >= 320
        title.text = work.title
        if (beside) {
            byline.text = context.getString(R.string.art_byline, work.artist, work.date)
            museum.text = work.museum
            museum.visibility = VISIBLE
        } else {
            byline.text = context.getString(R.string.art_byline_museum, work.artist, work.date, work.museum)
            museum.visibility = GONE
        }
        val gravity = if (beside) Gravity.START else Gravity.END
        for (line in listOf(title, byline, museum)) {
            line.gravity = gravity
            (line.layoutParams as LinearLayout.LayoutParams).gravity = gravity
            line.setTextColor(work.ink)
        }
        byline.alpha = 0.78f
        museum.alpha = 0.6f
        val maxWidth = ((if (beside) 1920 - FRAME - 44 - r.right - 40 else r.width()) * scale).roundToInt()
        label.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
        label.translationX = if (beside) (r.right + 44) * scale else r.right * scale - label.measuredWidth
        label.translationY = if (beside) r.bottom * scale - label.measuredHeight + 6 * scale else (r.bottom + 16) * scale
        label.layoutParams = (label.layoutParams as LayoutParams).apply { width = label.measuredWidth }
    }

    private fun text(face: android.graphics.Typeface, px: Float) = TextView(context).apply {
        typeface = face
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px * resources.displayMetrics.widthPixels / 1920f)
        includeFontPadding = false
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 3
    }

    /** Evenings and nights a little dimmer, as a real painting looks under room light. */
    private fun nightDim(): Float {
        val now = LocalTime.now()
        val h = now.hour + now.minute / 60f
        return when {
            h < 6f -> NIGHT_DIM
            h < 7f -> NIGHT_DIM * (7f - h)
            h < 20f -> 0f
            h < 23f -> NIGHT_DIM * (h - 20f) / 3f
            else -> NIGHT_DIM
        }
    }

    private companion object {
        const val FRAME = 60 // moulding width in render.py
        const val DWELL_MS = 3 * 60_000L
        const val PREFETCH_MS = 15_000L
        const val FADE_MS = 2_600L
        const val STEP_FADE_MS = 900L
        const val HISTORY = 200
        const val LABEL_DELAY_MS = 900L
        const val LABEL_FADE_MS = 1_200L
        const val LABEL_SHOWN_MS = 20_000L
        const val NIGHT_DIM = 0.22f
    }
}

/** Shows one finished picture and cross-fades to the next; redraws only while fading. */
private class ArtView(context: Context) : View(context) {
    private var shown: Bitmap? = null
    private var coming: Bitmap? = null
    private var fade = 0f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bounds = Rect()

    fun fadeTo(next: Bitmap, duration: Long, done: () -> Unit) {
        // Only a fade still running is finished early: end() on a finished animator replays its end.
        animator?.takeIf { it.isStarted }?.end()
        coming = next
        fade = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = PathInterpolator(0.45f, 0f, 0.25f, 1f)
            addUpdateListener { fade = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    shown = coming
                    coming = null
                    invalidate()
                    done()
                }
            })
            start()
        }
    }

    fun clear() {
        animator?.cancel()
        shown = null
        coming = null
    }

    override fun onDraw(canvas: Canvas) {
        bounds.set(0, 0, width, height)
        shown?.let { canvas.drawBitmap(it, null, bounds, null) }
        coming?.let {
            paint.alpha = (fade * 255).roundToInt()
            canvas.drawBitmap(it, null, bounds, paint)
        }
    }
}
