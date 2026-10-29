package site.gehlot.rattan

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Remote buttons the stock home screen used to handle, plus a clean-up button. Runs as an
 * accessibility service that only sees key presses, never the screen; every other key passes on.
 */
class RemoteKeys : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val action: (() -> Unit) = when (event.keyCode) {
            KEY_NETFLIX -> { { Apps.launchPackage(this, NETFLIX) } }
            KEY_YOUTUBE -> { { Apps.launchPackage(this, YOUTUBE) } }
            KEY_TV -> { { LiveTv.open(this) } }
            KEY_CLEAN_UP -> { { Cleanup.run(this) } }
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) action()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private companion object {
        // TCL's own key codes on the BT_RC833A remote, read from it on 2026-10-29.
        const val KEY_NETFLIX = 4062
        const val KEY_YOUTUBE = 4065
        const val KEY_TV = 4057
        const val KEY_CLEAN_UP = 4020
        const val NETFLIX = "com.netflix.ninja"
        const val YOUTUBE = "com.google.android.youtube.tv"
    }
}

/**
 * Goes home, then closes every app that is neither on screen nor doing something the user can
 * notice. Android keeps an app that is playing (Spotify with music on) and closes it when idle.
 */
object Cleanup {
    private val main = Handler(Looper.getMainLooper())

    /** Background services worth closing besides the apps themselves. */
    private val EXTRA = listOf("com.android.tv.settings", "com.google.android.tts", "com.android.vending")

    fun run(context: Context) {
        start(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName))
        // Give the app that was on screen a moment to move to the background before closing it.
        main.postDelayed({
            val am = context.getSystemService(ActivityManager::class.java)
            (Apps.packages(context) + EXTRA).filter { it != context.packageName }.forEach(am::killBackgroundProcesses)
            main.postDelayed({
                val memory = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
                val freeMb = memory.availMem / (1024 * 1024)
                Toast.makeText(context, context.getString(R.string.cleaned_up, freeMb), Toast.LENGTH_SHORT).show()
            }, 800)
        }, 700)
    }
}
