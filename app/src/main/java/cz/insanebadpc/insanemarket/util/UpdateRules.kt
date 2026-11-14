package cz.insanebadpc.insanemarket.util

/**
 * Rozhoduje, jestli je dostupná aktualizace, a učí se, která release verze je
 * aktuálně nainstalovaná. Nepoužívá odhad podle názvu repozitáře: update se
 * hlásí jen tehdy, když to lze doložit staženým APK (versionCode), dříve
 * zaznamenaným tagem, nebo porovnáním nainstalované versionName s tagem vydání.
 */
object UpdateRules {
    data class Decision(val hasUpdate: Boolean, val learnedTag: String?)

    fun decide(
        installedVersionCode: Long?,
        installedTag: String?,
        knownTag: String?,
        knownVersionCode: Long?,
        latestTag: String?,
        installedVersionName: String? = null,
    ): Decision {
        if (installedVersionCode == null || latestTag == null) return Decision(false, installedTag)

        val proofTag = if (knownVersionCode != null && installedVersionCode >= knownVersionCode) knownTag else null
        val effectiveTag = installedTag ?: proofTag

        val versionUpdate = isNewerVersion(installedVersionName, latestTag)

        if (effectiveTag == null) {
            return if (versionUpdate == true) Decision(true, null) else Decision(false, null)
        }

        val outOfBandUpdate = installedTag != null &&
            knownVersionCode != null &&
            knownTag == installedTag &&
            installedVersionCode > knownVersionCode
        if (outOfBandUpdate) {
            return if (versionUpdate == true) Decision(true, installedTag) else Decision(false, latestTag)
        }

        if (versionUpdate == false && effectiveTag != latestTag) {
            // Nainstalovaná verze je stejná nebo novější než latestTag (např. jiný formát tagu)
            return Decision(false, latestTag)
        }

        return Decision(effectiveTag != latestTag, effectiveTag)
    }

    /**
     * Porovná nainstalovanou `versionName` (např. "0.3.9") s tagem vydání (např. "app-v0.3.11").
     * Vrací `true` pokud je `latestTag` prokazatelně novější, `false` pokud je stejný nebo starší,
     * a `null` pokud se verze nedají bezpečně porovnat.
     */
    fun isNewerVersion(installedVersionName: String?, latestTag: String?): Boolean? {
        val installedParts = parseVersionParts(installedVersionName) ?: return null
        val latestParts = parseVersionParts(latestTag) ?: return null
        if (installedParts.size < 2 || latestParts.size < 2) return null

        val maxLen = maxOf(installedParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val a = installedParts.getOrElse(i) { 0 }
            val b = latestParts.getOrElse(i) { 0 }
            if (b > a) return true
            if (b < a) return false
        }
        return false
    }

    private fun parseVersionParts(raw: String?): List<Int>? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("""\d+(?:\.\d+)+""").find(raw) ?: return null
        return match.value.split('.').mapNotNull { it.toIntOrNull() }
    }
}
