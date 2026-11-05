package site.gehlot.rattan

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Remote buttons the stock home screen used to handle, plus a clean-up button. Runs as an
 * accessibility service that only sees key presses, never the screen; every other key passes on.
 */
class RemoteKeys : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val action: (() -> Unit) = when (event.keyCode) {
            KEY_NETFLIX -> { { Apps.launchPackage(this, NETFLIX) } }
            KEY_YOUTUBE -> { { Apps.launchPackage(this, YOUTUBE) } }
            KEY_PRIME_VIDEO -> { { Apps.launchPackage(this, PRIME_VIDEO) } }
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
        // Read on 2026-11-05: since TCL's Android 14 update, TCL hands this key to the Google TV home.
        const val KEY_PRIME_VIDEO = 4099
        const val KEY_TV = 4057
        const val KEY_CLEAN_UP = 4020
        const val NETFLIX = "com.netflix.ninja"
        const val YOUTUBE = "com.google.android.youtube.tv"
        const val PRIME_VIDEO = "com.amazon.amazonvideo.livingroom"
    }
}

/**
 * Stops whatever is playing, goes home, then closes the apps. Media apps are force-stopped through
 * the TV's own ADB ([Adb]), because a paused player (Spotify) keeps a foreground service that no
 * ordinary app may close. TCL and Android system apps, and everything when ADB is unavailable, get
 * the gentle close, which leaves anything still holding a foreground service running.
 */
object Cleanup {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    /** Background services worth closing besides the apps themselves. */
    private val EXTRA = listOf("com.android.tv.settings", "com.google.android.tts", "com.android.vending")

    /** Launcher apps that are part of the TV itself: never force-stopped. */
    private fun isSystemPart(pkg: String) = pkg.startsWith("com.tcl.") || pkg.startsWith("com.android.")

    fun run(context: Context) {
        if (!running.compareAndSet(false, true)) return
        pausePlayback(context)
        start(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName))
        // Give the app that was on screen a moment to move to the background before closing it.
        main.postDelayed({
            val apps = (Apps.packages(context) + EXTRA).distinct().filter { it != context.packageName }
            val media = apps.filterNot(::isSystemPart)
            Thread {
                val forced = media.isNotEmpty() &&
                    Adb.shell(context, media.joinToString("; ") { "cmd activity force-stop $it" }) != null
                main.post {
                    val am = context.getSystemService(ActivityManager::class.java)
                    (if (forced) apps - media.toSet() else apps).forEach(am::killBackgroundProcesses)
                    main.postDelayed({
                        val memory = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
                        val freeMb = memory.availMem / (1024 * 1024)
                        val text = if (forced) R.string.closed_all else R.string.cleaned_up
                        Toast.makeText(context, context.getString(text, freeMb), Toast.LENGTH_SHORT).show()
                        running.set(false)
                    }, 800)
                }
            }.start()
        }, 700)
    }

    /** Pauses the active player, as the remote's pause button would. */
    private fun pausePlayback(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        if (!audio.isMusicActive) return
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
    }
}
