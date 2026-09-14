package dev.otto.phone.device

import android.content.Context
import android.content.Intent
import android.net.Uri

/** The Play Store listing for a package or a search; the accessibility
 *  service then taps Install (free apps only -- a price on the button means
 *  money, and money is the person's). */
object PlayStore {
    const val PACKAGE = "com.android.vending"
    val PRICE = Regex("[₹$€£]\\s*[\\d,.]+|\\bbuy\\b", RegexOption.IGNORE_CASE)

    fun open(context: Context, packageName: String, query: String): Boolean {
        val uri = if (packageName.isNotBlank()) "market://details?id=$packageName" else "market://search?q=${Uri.encode(query)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); true } catch (e: Exception) { false }
    }
}
