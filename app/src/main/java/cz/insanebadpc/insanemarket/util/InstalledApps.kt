package cz.insanebadpc.insanemarket.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class InstalledInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val launchable: Boolean,
)

object InstalledApps {
    fun byPackage(context: Context): Map<String, InstalledInfo> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        val out = LinkedHashMap<String, InstalledInfo>(packages.size)
        for (info in packages) {
            val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            val label = runCatching {
                info.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
            }.getOrNull().orEmpty().ifBlank { info.packageName }
            val launchable = runCatching { pm.getLaunchIntentForPackage(info.packageName) != null }
                .getOrDefault(false)
            out[info.packageName] = InstalledInfo(
                packageName = info.packageName,
                label = label,
                versionName = info.versionName,
                versionCode = versionCode,
                launchable = launchable,
            )
        }
        return out
    }

    fun launchable(context: Context): List<InstalledInfo> =
        byPackage(context).values
            .filter { it.launchable }
            .sortedBy { it.label.lowercase() }

    fun exact(installed: Map<String, InstalledInfo>, packageName: String?): InstalledInfo? {
        if (packageName.isNullOrBlank()) return null
        return installed[packageName]
    }
}
