package site.gehlot.rattan

import android.content.res.AssetManager
import android.graphics.Typeface

/** Instrument Serif for the clock and titles, Inter for everything small. Both SIL OFL 1.1. */
object Type {
    lateinit var display: Typeface
        private set
    lateinit var displayItalic: Typeface
        private set
    lateinit var text: Typeface
        private set
    lateinit var textStrong: Typeface
        private set

    fun load(assets: AssetManager) {
        if (::display.isInitialized) return
        display = Typeface.createFromAsset(assets, "fonts/InstrumentSerif-Regular.ttf")
        displayItalic = Typeface.createFromAsset(assets, "fonts/InstrumentSerif-Italic.ttf")
        text = inter(assets, 450)
        textStrong = inter(assets, 600)
    }

    private fun inter(assets: AssetManager, weight: Int) =
        Typeface.Builder(assets, "fonts/Inter.ttf").setFontVariationSettings("'wght' $weight, 'opsz' 16").build()
}
