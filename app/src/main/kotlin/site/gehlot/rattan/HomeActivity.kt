package site.gehlot.rattan

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class HomeActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var stage: StageView
    private lateinit var appGrid: LinearLayout
    private lateinit var inputRow: LinearLayout
    private var appsStale = true
    private var resumed = false
    private val density by lazy { resources.displayMetrics.density }
    private val location = IntArray(2)
    private var shownPanels = FloatArray(0)

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appsStale = true
            if (resumed) reloadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        root = findViewById(R.id.root)
        appGrid = findViewById(R.id.app_grid)
        // Room at the sides, so a grown tile in the first or last column stays inside the grid.
        appGrid.setPadding(dp(EDGE), 0, dp(EDGE), 0)
        appGrid.clipToPadding = false
        inputRow = findViewById(R.id.input_row)
        stage = StageView(this) { showPlainBackground() }
        root.addView(stage, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.viewTreeObserver.addOnGlobalLayoutListener { syncGlass() }

        findViewById<GlassLayout>(R.id.settings).apply {
            val icon = getChildAt(0) as ImageView
            icon.isDuplicateParentStateEnabled = true
            icon.imageTintList = focusColors()
            focusPill(this)
            setOnClickListener { start(context, Intent(Settings.ACTION_SETTINGS)) }
        }
        if (LiveTv.available(this)) inputRow.addView(chip(getString(R.string.live_tv)) { LiveTv.open(this) })
        Inputs.load(this).forEach { input -> inputRow.addView(chip(input.label) { Inputs.open(this, input) }) }
        keepFocusInRow(inputRow)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageChanges, filter, RECEIVER_NOT_EXPORTED)
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
        appGrid.post { firstTile()?.requestFocus() }
    }

    private fun firstTile(): View? = (appGrid.getChildAt(0) as? ViewGroup)?.getChildAt(0)

    /** Tells the stage where every glass panel is. */
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
        // Layout passes happen often; the glass only redraws when a panel really moved.
        if (data.contentEquals(shownPanels)) return
        shownPanels = data
        stage.show(data)
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

    /** Up to seven apps per row, so the dock holds every app without scrolling. */
    private fun reloadApps() {
        appsStale = false
        appGrid.removeAllViews()
        Apps.load(this).chunked(COLUMNS).forEachIndexed { r, apps ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            apps.forEachIndexed { c, app ->
                row.addView(appTile(app), LinearLayout.LayoutParams(dp(TILE_W + 2 * RING), dp(TILE_H + 2 * RING)).apply {
                    if (c > 0) marginStart = dp(GAP - 2 * RING)
                })
            }
            // Room above and below, so a grown tile stays inside its row.
            row.setPadding(0, dp(ROOM), 0, dp(ROOM))
            keepFocusInRow(row)
            appGrid.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                if (r > 0) topMargin = dp(GAP - 2 * RING - 2 * ROOM)
            })
        }
    }

    /**
     * A banner with a few dp of room around it, where the focus ring is drawn. The ring is a fixed
     * foreground that shows only while focused, so moving focus is a repaint, never a new layout.
     */
    private fun appTile(app: AppEntry): View {
        val radius = 10 * density
        val ring = dp(RING)
        val face = FrameLayout(this).apply {
            outlineProvider = roundOutline(radius)
            clipToOutline = true
        }
        val banner = app.banner
        if (banner != null) {
            face.addView(ImageView(this).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            })
        } else {
            // No banner: the app's icon and name on a dark pane.
            face.setBackgroundColor(0x8C000000.toInt())
            face.addView(LinearLayout(this).apply {
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
        val tile = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            contentDescription = app.label
            // The shadow follows the banner, not the ring around it.
            outlineProvider = roundOutline(radius, ring)
            addView(face, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(ring, ring, ring, ring) })
            foreground = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), FocusRing(radius + ring, density))
            }
        }
        Focus.lift(tile, 1.06f)
        tile.setOnClickListener { Apps.launch(this, app) }
        tile.setOnLongClickListener {
            Apps.openDetails(this, app)
            true
        }
        return tile
    }

    private fun chip(text: CharSequence, onClick: () -> Unit): View = GlassLayout(this).apply {
        cornerRadius = 20 * density
        isFocusable = true
        isClickable = true
        setPadding(dp(22), 0, dp(22), 0)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { marginStart = dp(6); marginEnd = dp(6) }
        val label = label(text, 15f).apply {
            isDuplicateParentStateEnabled = true
            setTextColor(focusColors())
        }
        addView(label, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        // Dark text on the light pill needs no shadow; light text on glass does.
        focusPill(this) { focused -> label.setShadowLayer(if (focused) 0f else 6 * density, 0f, density, SHADOW) }
        setOnClickListener { onClick() }
    }

    /**
     * Focus on a glass control: it turns into a solid light pill, lifted a little, with dark content.
     * The pill is the view's own background, so it follows the remote without delay; the glass
     * behind it never changes.
     */
    private fun focusPill(view: GlassLayout, onChange: (Boolean) -> Unit = {}) {
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(getColor(R.color.text))
                cornerRadius = view.cornerRadius
            })
        }
        view.outlineProvider = roundOutline(view.cornerRadius)
        Focus.lift(view, 1.06f, onChange)
    }

    private fun focusColors() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(getColor(R.color.ink), getColor(R.color.text)),
    )

    private fun label(text: CharSequence, sp: Float) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setShadowLayer(6 * density, 0f, density, SHADOW)
    }

    private companion object {
        const val COLUMNS = 7
        const val SHADOW = 0xA6000000.toInt()
        const val TILE_W = 106
        const val TILE_H = 60
        const val GAP = 14
        const val RING = 4
        const val ROOM = 3
        const val EDGE = 5
    }
}
