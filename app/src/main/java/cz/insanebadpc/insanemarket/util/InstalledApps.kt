package cz.insanebadpc.insanemarket.util

import android.content.Context
import android.os.Build

data class InstalledInfo(val packageName: String, val versionName: String?, val versionCode: Long)

object InstalledApps {
    fun byPackage(context: Context): Map<String, InstalledInfo> {
        val pm = context.packageManager
        val pkgs = if (Build.VERSION.SDK_INT >= 33)
            pm.getInstalledPackages(android.content.pm.PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getInstalledPackages(0)
        return pkgs.associate { p ->
            val vc = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
            p.packageName to InstalledInfo(p.packageName, p.versionName, vc)
        }
    }

    fun match(entryPackage: String?, mappedPackage: String?, repoName: String, installed: Map<String, InstalledInfo>): InstalledInfo? {
        entryPackage?.let { installed[it] }?.let { return it }
        mappedPackage?.let { installed[it] }?.let { return it }
        val n = repoName.lowercase().replace("[^a-z0-9]".toRegex(), "")
        if (n.isBlank()) return null
        return installed.values.firstOrNull {
            val p = it.packageName.lowercase()
            val last = p.substringAfterLast('.').replace("[^a-z0-9]".toRegex(), "")
            p.contains(n) || n.contains(last) || last.contains(n)
        }
    }
}
