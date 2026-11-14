package cz.insanebadpc.insanemarket.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cz.insanebadpc.insanemarket.R
import cz.insanebadpc.insanemarket.databinding.ItemAppBinding
import cz.insanebadpc.insanemarket.model.AppEntry
import cz.insanebadpc.insanemarket.util.ByteFormat
import cz.insanebadpc.insanemarket.util.IconLoader

class AppAdapter(
    private val currentSection: () -> MarketSection,
    private val isSavedExternal: (AppEntry) -> Boolean,
    private val onSaveExternal: (AppEntry) -> Unit,
    private val onAction: (AppEntry) -> Unit,
    private val onOpen: (AppEntry) -> Unit,
    private val onOpenWeb: (AppEntry) -> Unit,
    private val onRate: (AppEntry, Int) -> Unit,
    private val onToggleHide: (AppEntry) -> Unit,
    private val onEdit: (AppEntry) -> Unit,
    private val onDetails: (AppEntry) -> Unit,
) : ListAdapter<AppEntry, AppAdapter.VH>(Diff) {
    object Diff : DiffUtil.ItemCallback<AppEntry>() {
        override fun areItemsTheSame(oldItem: AppEntry, newItem: AppEntry) =
            oldItem.repoFullName.equals(newItem.repoFullName, ignoreCase = true)

        override fun areContentsTheSame(oldItem: AppEntry, newItem: AppEntry) = oldItem == newItem
    }

    class VH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.b
        val context = b.root.context
        val section = currentSection()

        IconLoader.bind(b.appIcon, item)

        b.name.text = item.displayName
        b.repo.text = buildList {
            add(item.repoFullName.removePrefix("manual:"))
            item.language?.let(::add)
            if (item.starsCount > 0) add("⭐ ${item.starsCount}")
            item.packageName?.takeIf { item.isInstalled }?.let(::add)
        }.joinToString(" • ")

        b.version.text = buildList {
            val ver = item.latestName ?: item.latestTag
            if (!ver.isNullOrBlank()) add(ver)
            item.latestTag?.takeIf { it != item.latestName }?.let(::add)
            item.assetSize?.let { add(ByteFormat.bytes(it)) }
            item.installedVersion?.let { add("nainstalováno $it") }
            if (section == MarketSection.WEB && !item.webUrl.isNullOrBlank()) {
                add(item.webUrl!!.removePrefix("https://").removePrefix("http://").trimEnd('/'))
            }
        }.ifEmpty { listOf(context.getString(R.string.no_version)) }.joinToString(" • ")

        // Platform & capability badges
        b.badgeApk.visibility = if (item.hasApk) View.VISIBLE else View.GONE
        b.badgeWeb.visibility = if (item.hasWebApp) View.VISIBLE else View.GONE
        val savedInExternal = isSavedExternal(item)
        when {
            item.isExternal && !savedInExternal -> {
                b.badgeExtra.visibility = View.VISIBLE
                b.badgeExtra.text = "🔍 GITHUB HLEDÁNÍ"
            }
            item.isExternal -> {
                b.badgeExtra.visibility = View.VISIBLE
                b.badgeExtra.text = "📦 CIZÍ REPO"
            }
            item.isPrivate -> {
                b.badgeExtra.visibility = View.VISIBLE
                b.badgeExtra.text = "🔒 SOUKROMÉ"
            }
            item.isManual -> {
                b.badgeExtra.visibility = View.VISIBLE
                b.badgeExtra.text = "✨ RUČNÍ"
            }
            else -> b.badgeExtra.visibility = View.GONE
        }

        // Star rating 1..5
        val stars = listOf(b.star1, b.star2, b.star3, b.star4, b.star5)
        val goldColor = ContextCompat.getColor(context, R.color.market_star)
        val emptyColor = ContextCompat.getColor(context, R.color.market_star_empty)
        stars.forEachIndexed { idx, starView ->
            val starNum = idx + 1
            val filled = item.userRating >= starNum
            starView.text = if (filled) "★" else "☆"
            starView.setTextColor(if (filled) goldColor else emptyColor)
            starView.setOnClickListener { onRate(item, starNum) }
        }
        b.ratingLabel.text = if (item.userRating > 0) "${item.userRating}/5 ★" else "Ohodnoť ★"

        // Hide / Unhide button
        b.btnHide.text = context.getString(if (item.isHidden) R.string.unhide else R.string.hide)
        b.btnHide.setOnClickListener { onToggleHide(item) }

        b.desc.text = item.description.lineSequence()
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString(" • ")
            .take(220)
            .ifBlank { context.getString(R.string.app_fallback_description) }

        b.status.text = when {
            item.isDownloading && item.downloadProgress > 0 -> context.getString(R.string.status_downloading, item.downloadProgress)
            item.isDownloading -> context.getString(R.string.status_downloading_start)
            item.downloadReady -> context.getString(R.string.status_downloaded)
            item.hasUpdate -> context.getString(R.string.status_update, item.latestTag.orEmpty())
            item.isInstalled -> context.getString(R.string.status_installed)
            !item.note.isNullOrBlank() -> item.note
            section == MarketSection.EXTERNAL -> context.getString(R.string.status_external_ready)
            section == MarketSection.WEB && !item.hasApk -> context.getString(R.string.status_web_ready)
            else -> context.getString(R.string.status_ready)
        }

        b.badge.visibility = if (item.hasUpdate || item.isInstalled) View.VISIBLE else View.GONE
        b.badge.text = when {
            item.hasUpdate -> context.getString(R.string.badge_update)
            else -> context.getString(R.string.badge_installed)
        }
        b.badge.setTextColor(
            ContextCompat.getColor(
                context,
                if (item.hasUpdate) R.color.market_warning else R.color.market_success,
            )
        )

        // Section-aware buttons
        b.action.isEnabled = true
        when (section) {
            MarketSection.APK -> {
                b.action.text = apkActionText(context, item)
                b.action.setOnClickListener { onAction(item) }
                if (item.hasWebApp) {
                    b.webAction.visibility = View.VISIBLE
                    b.webAction.text = context.getString(R.string.open_web)
                    b.webAction.setOnClickListener { onOpenWeb(item) }
                } else {
                    b.webAction.visibility = View.GONE
                }
            }
            MarketSection.WEB -> {
                b.action.text = context.getString(R.string.open_web)
                b.action.setOnClickListener { onOpenWeb(item) }
                if (item.hasApk) {
                    b.webAction.visibility = View.VISIBLE
                    b.webAction.text = apkActionText(context, item)
                    b.webAction.setOnClickListener { onAction(item) }
                } else {
                    b.webAction.visibility = View.GONE
                }
            }
            MarketSection.EXTERNAL -> {
                b.action.text = context.getString(R.string.explore_repo)
                b.action.setOnClickListener { onDetails(item) }
                when {
                    !savedInExternal -> {
                        b.webAction.visibility = View.VISIBLE
                        b.webAction.text = context.getString(R.string.save_external_btn)
                        b.webAction.setOnClickListener { onSaveExternal(item) }
                    }
                    item.hasWebApp -> {
                        b.webAction.visibility = View.VISIBLE
                        b.webAction.text = context.getString(R.string.open_web)
                        b.webAction.setOnClickListener { onOpenWeb(item) }
                    }
                    item.hasApk -> {
                        b.webAction.visibility = View.VISIBLE
                        b.webAction.text = apkActionText(context, item)
                        b.webAction.setOnClickListener { onAction(item) }
                    }
                    else -> b.webAction.visibility = View.GONE
                }
            }
        }

        b.open.visibility = if (item.isInstalled && !item.packageName.isNullOrBlank()) View.VISIBLE else View.GONE
        b.itemProgress.visibility = if (item.isDownloading) View.VISIBLE else View.GONE
        b.itemProgress.isIndeterminate = item.isDownloading && item.downloadProgress <= 0
        if (!b.itemProgress.isIndeterminate) b.itemProgress.progress = item.downloadProgress

        b.open.setOnClickListener { onOpen(item) }
        b.edit.setOnClickListener { onEdit(item) }
        b.root.setOnClickListener { onDetails(item) }
    }

    private fun apkActionText(context: android.content.Context, item: AppEntry): String = when {
        item.isDownloading -> context.getString(R.string.cancel)
        item.downloadReady -> context.getString(R.string.install)
        item.hasUpdate -> context.getString(R.string.update)
        item.isInstalled -> context.getString(R.string.reinstall)
        else -> context.getString(R.string.download_install)
    }
}
