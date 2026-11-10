package cz.insanebadpc.insanemarket.util

/**
 * Rozhoduje, jestli je dostupná aktualizace, a učí se, která release verze je
 * aktuálně nainstalovaná. Nepoužívá odhad podle názvu repozitáře: update se
 * hlásí jen tehdy, když to lze doložit staženým APK (versionCode) nebo dříve
 * zaznamenaným tagem.
 */
object UpdateRules {
    data class Decision(val hasUpdate: Boolean, val learnedTag: String?)

    fun decide(
        installedVersionCode: Long?,
        installedTag: String?,
        knownTag: String?,
        knownVersionCode: Long?,
        latestTag: String?,
    ): Decision {
        if (installedVersionCode == null || latestTag == null) return Decision(false, installedTag)

        val proofTag = if (knownVersionCode != null && installedVersionCode >= knownVersionCode) knownTag else null
        val effectiveTag = installedTag ?: proofTag
        if (effectiveTag == null) return Decision(false, null)

        val outOfBandUpdate = installedTag != null &&
            knownVersionCode != null &&
            knownTag == installedTag &&
            installedVersionCode > knownVersionCode
        if (outOfBandUpdate) return Decision(false, latestTag)

        return Decision(effectiveTag != latestTag, effectiveTag)
    }
}
