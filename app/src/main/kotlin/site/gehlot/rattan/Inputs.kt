package site.gehlot.rattan

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri

class InputEntry(val id: String, val label: CharSequence)

/** The TV's HDMI inputs, as the system lists them. */
object Inputs {
    fun load(context: Context): List<InputEntry> {
        val manager = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        return manager.tvInputList
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null && !it.isHidden(context) }
            .sortedBy { it.id }
            .map { InputEntry(it.id, it.loadCustomLabel(context) ?: it.loadLabel(context)) }
    }

    fun open(context: Context, input: InputEntry) {
        val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            // On TCL TVs only the live-TV app takes passthrough links, and only when named directly.
            context.startActivity(Intent(intent).setClassName("com.tcl.tv", "com.tcl.player.TVActivity"))
        } catch (_: ActivityNotFoundException) {
            start(context, intent)
        }
    }
}

/** The FRITZ!Box's cable TV channels (its HD list), played in VLC over the home network. */
object LiveTv {
    private const val PLAYER = "org.videolan.vlc"
    private val PLAYLIST = Uri.parse("http://192.168.178.1/dvb/m3u/tvhd.m3u")

    fun available(context: Context) = context.packageManager.getLeanbackLaunchIntentForPackage(PLAYER) != null

    fun open(context: Context) =
        start(context, Intent(Intent.ACTION_VIEW).setDataAndType(PLAYLIST, "audio/x-mpegurl").setPackage(PLAYER))
}
