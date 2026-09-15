package dev.otto.phone.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class InstalledApp(val label: String, val packageName: String)

/** Installed apps with a launcher icon (what <queries> allows), and launching one. */
class AppCatalog(private val context: Context) {
    /** Labels found, by package: every snapshot asks for the one in front. A miss is never kept, so an
     *  app installed a moment later is looked up again. */
    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun list(): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun label(packageName: String): String = labels[packageName] ?: try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().also { labels[packageName] = it }
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    /** After an install or update, when a package's label may have changed. */
    fun forgetLabels() = labels.clear()

    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
        return true
    }
}
