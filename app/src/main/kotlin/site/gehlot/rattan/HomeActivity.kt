package site.gehlot.rattan

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.ceil

class HomeActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var stage: StageView
    private lateinit var lens: FocusLens
    private lateinit var clock: View
    private lateinit var appGrid: LinearLayout
    private lateinit var inputRow: LinearLayout
    private lateinit var caption: TextView
    private lateinit var title: TextView
    private var appsStale = true
    private var resumed = false
    private val density by lazy { resources.displayMetrics.density }
    private val location = IntArray(2)
    private var shownPanels = FloatArray(0)
    private var shownScrim = FloatArray(0)

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appsStale = true
            if (resumed) reloadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Type.load(assets)
        setContentView(R.layout.activity_home)
        root = findViewById(R.id.root)
        clock = findViewById(R.id.clock)
        appGrid = findViewById(R.id.app_grid)
        inputRow = findViewById(R.id.input_row)
        caption = findViewById(R.id.caption)
        title = findViewById(R.id.title)
        findViewById<TextClock>(R.id.time).typeface = Type.display
        findViewById<TextClock>(R.id.date).typeface = Type.text
        caption.typeface = Type.text
        title.typeface = Type.displayItalic

        stage = StageView(this) { showPlainBackground() }
        root.addView(stage, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        lens = FocusLens(this)
        root.addView(lens, FrameLayout.LayoutParams(0, 0))
        root.viewTreeObserver.addOnGlobalLayoutListener {
            syncGlass()
            lens.refresh()
        }
        root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new -> focusMoved(old, new) }

        findViewById<TextView>(R.id.credit).typeface = Type.text
        findViewById<GlassLayout>(R.id.settings).apply {
            tag = LensSpot(GROUP_SYSTEM, cornerRadius, ring = false, caption = getString(R.string.caption_system))
            setOnClickListener { start(context, Intent(Settings.ACTION_SETTINGS)) }
        }
        if (LiveTv.available(this)) inputRow.addView(segment(getString(R.string.live_tv)) { LiveTv.open(this) })
        Inputs.load(this).forEach { input -> inputRow.addView(segment(input.label) { Inputs.open(this, input) }) }
        keepFocusInRow(inputRow)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageChanges, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageChanges, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(packageChanges)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        stage.onResume()
        if (appsStale) reloadApps()
        focusFirstApp()
    }

    override fun onPause() {
        resumed = false
        stage.onPause()
        super.onPause()
    }

    /** Home pressed while already home: back to the first app. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        focusFirstApp()
    }

    /** Back does nothing on the home screen. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(event)

    /** If OpenGL is unavailable, the photo alone, without glass; the launcher keeps working. */
    private fun showPlainBackground() {
        stage.visibility = View.GONE
        val photo = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resources, R.drawable.wallpaper)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        }
        window.setBackgroundDrawable(BitmapDrawable(resources, photo))
    }

    private fun focusFirstApp() {
        appGrid.post { (appGrid.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus() }
    }

    /** The lens glides within a group and reappears between groups; the title names what has focus. */
    private fun focusMoved(old: View?, new: View?) {
        val spot = new?.tag as? LensSpot
        val glide = spot != null && (old?.tag as? LensSpot)?.group == spot.group
        lens.follow(new, glide)
        val name = new?.contentDescription
        if (spot == null || name == null || name == title.text) return
        caption.text = spot.caption
        title.text = name
        for (line in listOf(caption, title)) {
            line.alpha = 0f
            line.translationX = -10 * density
            line.animate().alpha(1f).translationX(0f).setDuration(280).setInterpolator(EASE).start()
        }
    }

    /** Tells the stage where every glass panel is, and where the clock sits for its dark backdrop. */
    private fun syncGlass() {
        val panels = ArrayList<GlassLayout>()
        collectGlass(root, panels)
        val height = stage.height.toFloat()
        val data = FloatArray(panels.size * 5)
        panels.forEachIndexed { i, panel ->
            panel.getLocationInWindow(location)
            val halfW = panel.width / 2f
            val halfH = panel.height / 2f
            data[i * 5] = location[0] + halfW
            data[i * 5 + 1] = height - (location[1] + halfH)
            data[i * 5 + 2] = halfW
            data[i * 5 + 3] = halfH
            data[i * 5 + 4] = panel.cornerRadius
        }
        clock.getLocationInWindow(location)
        val scrim = floatArrayOf(
            location[0] + clock.width / 2f,
            height - (location[1] + clock.height / 2f),
            clock.width * 0.95f,
            clock.height * 1.1f,
        )
        // Layout passes happen often; the glass only redraws when something really moved.
        if (data.contentEquals(shownPanels) && scrim.contentEquals(shownScrim)) return
        shownPanels = data
        shownScrim = scrim
        stage.show(data, scrim)
    }

    private fun collectGlass(view: View, into: MutableList<GlassLayout>) {
        if (view is GlassLayout && view.isShown) into += view
        if (view is ViewGroup) for (i in 0 until view.childCount) collectGlass(view.getChildAt(i), into)
    }

    private fun dp(value: Int) = (value * density).toInt()

    /** Left on a row's first item and right on its last stay put, instead of jumping to another row. */
    private fun keepFocusInRow(row: ViewGroup) {
        if (row.childCount == 0) return
        val first = row.getChildAt(0)
        val last = row.getChildAt(row.childCount - 1)
        for (end in listOf(first, last)) if (end.id == View.NO_ID) end.id = View.generateViewId()
        first.nextFocusLeftId = first.id
        last.nextFocusRightId = last.id
    }

    /**
     * Every app in the dock: balanced rows of at most seven, tiles sized to fill the width exactly.
     * Apps change while home is showing (TCL updates its packages at boot), so if focus was in the
     * dock it returns to the same app, or the first one.
     */
    private fun reloadApps() {
        appsStale = false
        val focusedApp = if (appGrid.hasFocus()) currentFocus?.getTag(R.id.app_package) as? String else null
        val hadFocus = appGrid.hasFocus()
        appGrid.removeAllViews()
        val apps = Apps.load(this)
        if (apps.isEmpty()) return
        val rows = ceil(apps.size / MAX_COLUMNS.toDouble()).toInt()
        val columns = ceil(apps.size / rows.toDouble()).toInt()
        val gap = dp(GAP)
        val inner = resources.displayMetrics.widthPixels - 2 * dp(MARGIN) - 2 * dp(DOCK_PADDING)
        val w = (inner - gap * (columns - 1)) / columns
        val h = w * 9 / 16
        apps.chunked(columns).forEachIndexed { r, rowApps ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowApps.forEachIndexed { c, app ->
                val index = r * columns + c
                row.addView(appTile(app, index, apps.size), LinearLayout.LayoutParams(w, h).apply {
                    if (c > 0) marginStart = gap
                })
            }
            keepFocusInRow(row)
            appGrid.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                if (r > 0) topMargin = gap
            })
        }
        if (hadFocus) {
            val again = appGrid.findViewWithTagRecursively(focusedApp)
            if (again != null) appGrid.post { again.requestFocus() } else focusFirstApp()
        }
    }

    private fun ViewGroup.findViewWithTagRecursively(packageName: String?): View? {
        if (packageName == null) return null
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.getTag(R.id.app_package) == packageName) return child
            if (child is ViewGroup) child.findViewWithTagRecursively(packageName)?.let { return it }
        }
        return null
    }

    private fun appTile(app: AppEntry, index: Int, count: Int): View {
        val radius = 10 * density
        return FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            contentDescription = app.label
            tag = LensSpot(GROUP_APPS, radius, ring = true, caption = getString(R.string.caption_apps, index + 1, count))
            setTag(R.id.app_package, app.packageName)
            outlineProvider = roundOutline(radius)
            clipToOutline = true
            val banner = app.banner
            if (banner != null) {
                addView(ImageView(context).apply {
                    setImageDrawable(banner)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                })
            } else {
                // No banner: the app's icon and name on a dark pane.
                setBackgroundColor(0x8C000000.toInt())
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(ImageView(context).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(dp(28), dp(28)))
                    addView(label(app.label, 10f).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(6), dp(4), dp(6), 0)
                    })
                })
            }
            setOnClickListener { Apps.launch(context, app) }
            setOnLongClickListener {
                Apps.openDetails(context, app)
                true
            }
        }
    }

    /** One segment of the inputs control; the lens is its only focus state. */
    private fun segment(text: CharSequence, onClick: () -> Unit): View = label(text, 13f).apply {
        isFocusable = true
        isClickable = true
        contentDescription = text
        tag = LensSpot(GROUP_INPUTS, 20 * density, ring = false, caption = getString(R.string.caption_input))
        setPadding(dp(18), 0, dp(18), 0)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT)
        setOnClickListener { onClick() }
    }

    private fun label(text: CharSequence, sp: Float) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Type.text
        letterSpacing = 0.02f
        gravity = Gravity.CENTER
    }

    private companion object {
        const val GROUP_APPS = "apps"
        const val GROUP_INPUTS = "inputs"
        const val GROUP_SYSTEM = "system"
        const val MAX_COLUMNS = 7
        const val MARGIN = 56
        const val DOCK_PADDING = 20
        const val GAP = 16
        val EASE = DecelerateInterpolator(2f)
    }
}
