package site.gehlot.rattan

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class HomeActivity : Activity() {

    private lateinit var appScroll: HorizontalScrollView
    private lateinit var appRow: LinearLayout
    private lateinit var inputRow: LinearLayout
    private var appsStale = true
    private var resumed = false
    private val density by lazy { resources.displayMetrics.density }

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appsStale = true
            if (resumed) reloadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Backdrop.load(resources)
        window.setBackgroundDrawable(BitmapDrawable(resources, wallpaper()))
        setContentView(R.layout.activity_home)
        appScroll = findViewById(R.id.app_scroll)
        appRow = findViewById(R.id.app_row)
        inputRow = findViewById(R.id.input_row)

        findViewById<View>(R.id.settings).apply {
            Focus.lift(this, 1.08f)
            setOnClickListener { start(context, Intent(Settings.ACTION_SETTINGS)) }
        }
        Inputs.load(this).forEach { inputRow.addView(inputChip(it)) }

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
        if (appsStale) reloadApps()
        focusFirstApp()
    }

    override fun onPause() {
        resumed = false
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

    /** Decoded straight into graphics memory: the pixels never sit on the app's heap. */
    private fun wallpaper(): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resources, R.drawable.wallpaper)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        }

    private fun focusFirstApp() {
        appRow.post {
            appScroll.scrollTo(0, 0)
            appRow.getChildAt(0)?.requestFocus()
        }
    }

    private fun reloadApps() {
        appsStale = false
        appRow.removeAllViews()
        Apps.load(this).forEach { appRow.addView(appTile(it)) }
    }

    private fun dp(value: Int) = (value * density).toInt()

    private fun appTile(app: AppEntry): View {
        val radius = 14 * density
        val tile = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            contentDescription = app.label
            outlineProvider = roundOutline(radius)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(160), dp(90)).apply { marginEnd = dp(16) }
        }
        val banner = app.banner
        if (banner != null) {
            tile.addView(ImageView(this).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            })
        } else {
            // No banner: the app's icon and name on a dark pane.
            tile.setBackgroundColor(0x59000000)
            tile.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ImageView(context).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(label(app.label, 12f).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(8), dp(6), dp(8), 0)
                })
            })
        }
        val rim = FocusRim(radius, density)
        Focus.lift(tile, 1.1f) { focused -> tile.foreground = if (focused) rim else null }
        tile.setOnClickListener { Apps.launch(this, app) }
        tile.setOnLongClickListener {
            Apps.openDetails(this, app)
            true
        }
        return tile
    }

    private fun inputChip(input: InputEntry): View = GlassLayout(this).apply {
        cornerRadius = 20 * density
        isFocusable = true
        isClickable = true
        setPadding(dp(22), 0, dp(22), 0)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { marginEnd = dp(12) }
        addView(label(input.label, 15f), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        Focus.lift(this, 1.08f)
        setOnClickListener { Inputs.open(context, input) }
    }

    private fun label(text: CharSequence, sp: Float) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
    }
}
