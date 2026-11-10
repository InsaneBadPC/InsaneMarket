package cz.insanebadpc.insanemarket.util

import cz.insanebadpc.insanemarket.model.AppEntry

/**
 * Řeší situaci, kdy dva repozitáře publikují APK se stejným package name
 * (např. staré a nové repo stejné aplikace). Nainstalovanou aplikaci smí
 * zobrazit jen jeden z nich, jinak by seznam lhal.
 */
object InstalledResolution {
    fun preferred(candidates: List<AppEntry>, ownerRepo: String?): AppEntry? {
        if (candidates.isEmpty()) return null
        ownerRepo?.let { owner ->
            candidates.firstOrNull { it.repoFullName == owner }?.let { return it }
        }
        return candidates.maxWithOrNull(
            compareBy<AppEntry>({ it.publishedAt ?: "" }, { it.displayName.lowercase() })
        )
    }
}
