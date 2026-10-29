package site.gehlot.rattan

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings

class AppEntry(
    val packageName: String,
    val component: ComponentName,
    val label: CharSequence,
    val banner: Drawable?,
    val icon: Drawable,
)

/** The TV apps to show: pinned ones first in their set order, then the rest alphabetically. */
object Apps {
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val res = context.resources
        val pinned = res.getStringArray(R.array.pinned_packages).toList()
        val hidden = res.getStringArray(R.array.hidden_packages).toSet() + context.packageName
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .map { it.activityInfo }
            .filter { it.packageName !in hidden }
            .distinctBy { it.packageName }
            .map { info ->
                AppEntry(
                    packageName = info.packageName,
                    component = ComponentName(info.packageName, info.name),
                    label = info.loadLabel(pm),
                    banner = info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm),
                    icon = info.loadIcon(pm),
                )
            }
            .sortedWith(
                compareBy<AppEntry> { pinned.indexOf(it.packageName).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    .thenBy { it.label.toString().lowercase() },
            )
    }

    /** Package names of the launchable TV apps, without loading any banners. */
    fun packages(context: Context): List<String> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return context.packageManager.queryIntentActivities(query, 0).map { it.activityInfo.packageName }.distinct()
    }

    fun launchPackage(context: Context, packageName: String) {
        context.packageManager.getLeanbackLaunchIntentForPackage(packageName)?.let { start(context, it) }
    }

    fun launch(context: Context, app: AppEntry) {
        val intent = context.packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(app.component)
        start(context, intent)
    }

    /** The system's page for the app, where it can be stopped, cleared or uninstalled. */
    fun openDetails(context: Context, app: AppEntry) =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)))
}

fun start(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // The app went away between listing and launching; the next reload drops it.
    }
}
