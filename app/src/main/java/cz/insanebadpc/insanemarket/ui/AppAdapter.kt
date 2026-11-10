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

class AppAdapter(
    private val onAction: (AppEntry) -> Unit,
    private val onOpen: (AppEntry) -> Unit,
    private val onEdit: (AppEntry) -> Unit,
    private val onDetails: (AppEntry) -> Unit,
) : ListAdapter<AppEntry, AppAdapter.VH>(Diff) {
    object Diff : DiffUtil.ItemCallback<AppEntry>() {
        override fun areItemsTheSame(oldItem: AppEntry, newItem: AppEntry) =
            oldItem.repoFullName == newItem.repoFullName

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

        b.name.text = item.displayName
        b.repo.text = buildList {
            add(item.repoFullName)
            item.language?.let(::add)
            item.packageName?.takeIf { item.isInstalled }?.let { add(it) }
        }.joinToString(" • ")

        b.version.text = buildList {
            add(item.latestName ?: item.latestTag ?: "bez verze")
            item.latestTag?.takeIf { it != item.latestName }?.let(::add)
            item.assetSize?.let { add(ByteFormat.bytes(it)) }
            item.installedVersion?.let { add("nainstalováno $it") }
        }.joinToString(" • ")

        b.desc.text = item.description.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(180)
            .orEmpty()
            .ifBlank { context.getString(R.string.app_fallback_description) }

        b.status.text = when {
            item.isDownloading && item.downloadProgress > 0 -> context.getString(R.string.status_downloading, item.downloadProgress)
            item.isDownloading -> context.getString(R.string.status_downloading_start)
            item.downloadReady -> context.getString(R.string.status_downloaded)
            item.hasUpdate -> context.getString(R.string.status_update, item.latestTag.orEmpty())
            item.isInstalled -> context.getString(R.string.status_installed)
            !item.note.isNullOrBlank() -> item.note
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

        b.action.isEnabled = true
        b.action.text = when {
            item.isDownloading -> context.getString(R.string.cancel)
            item.downloadReady -> context.getString(R.string.install)
            item.hasUpdate -> context.getString(R.string.update)
            item.isInstalled -> context.getString(R.string.reinstall)
            else -> context.getString(R.string.download_install)
        }
        b.open.visibility = if (item.isInstalled && !item.packageName.isNullOrBlank()) View.VISIBLE else View.GONE
        b.itemProgress.visibility = if (item.isDownloading) View.VISIBLE else View.GONE
        b.itemProgress.isIndeterminate = item.isDownloading && item.downloadProgress <= 0
        if (!b.itemProgress.isIndeterminate) b.itemProgress.progress = item.downloadProgress

        b.action.setOnClickListener { onAction(item) }
        b.open.setOnClickListener { onOpen(item) }
        b.edit.setOnClickListener { onEdit(item) }
        b.root.setOnClickListener { onDetails(item) }
    }
}
