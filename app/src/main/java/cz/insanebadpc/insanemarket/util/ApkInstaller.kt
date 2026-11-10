package cz.insanebadpc.insanemarket.util

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.Locale

object ApkInstaller {
    data class ApkInfo(val packageName: String, val versionCode: Long, val versionName: String?)

    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}"),
        )
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            // Některá zařízení obrazovku nemají; uživatel povolí instalaci ručně.
        }
    }

    fun install(context: Context, file: File): Boolean {
        if (!canInstall(context)) return false
        if (!file.exists() || file.length() <= 0L) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri(file.name, uri)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun infoFromApk(context: Context, file: File): ApkInfo? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, 0)
            } ?: return null
            info.applicationInfo?.let {
                it.sourceDir = file.absolutePath
                it.publicSourceDir = file.absolutePath
            }
            val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            ApkInfo(info.packageName, versionCode, info.versionName)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Vrací true, jen když je balíček už nainstalovaný a jeho podpisový certifikát
     * se prokazatelně liší od APK. Instalace by v takovém případě stejně selhala.
     */
    fun hasSignatureMismatch(context: Context, file: File): Boolean {
        val pm = context.packageManager
        val archive = archivePackageInfo(context, file) ?: return false
        val installed = try {
            packageInfo(pm, archive.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (_: Exception) {
            return false
        }
        val a = certHashes(archive)
        val b = certHashes(installed)
        return a.isNotEmpty() && b.isNotEmpty() && a != b
    }

    private fun archivePackageInfo(context: Context, file: File): android.content.pm.PackageInfo? =
        try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(certFlags().toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, certFlags())
            }
        } catch (_: Exception) {
            null
        }

    private fun packageInfo(pm: PackageManager, packageName: String): android.content.pm.PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(certFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, certFlags())
        }

    private fun certFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun certHashes(info: android.content.pm.PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return emptySet()
        return signers.map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                .joinToString("") { String.format(Locale.US, "%02x", it) }
        }.toSet()
    }

    fun openInstalled(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
