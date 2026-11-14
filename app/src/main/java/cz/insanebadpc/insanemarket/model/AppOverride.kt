package cz.insanebadpc.insanemarket.model

data class AppOverride(
    val displayName: String?,
    val description: String?,
    val webUrl: String? = null,
    val apkUrl: String? = null,
    val iconUrl: String? = null,
)
