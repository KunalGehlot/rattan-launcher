package site.gehlot.rattan

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager

/**
 * Art Mode, opened from the home screen: the screensaver's gallery on demand, for as long as it is
 * left on. Left and right step through the paintings, OK shows or hides the wall label, Back leaves.
 */
class ArtActivity : Activity() {
    private lateinit var gallery: Gallery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Type.load(assets)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gallery = Gallery(this)
        setContentView(gallery)
    }

    override fun onStart() {
        super.onStart()
        gallery.start()
    }

    override fun onStop() {
        gallery.stop()
        super.onStop()
    }

    override fun onDestroy() {
        gallery.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> gallery.step(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> gallery.step(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) gallery.toggleLabel()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
