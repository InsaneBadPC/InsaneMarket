package cz.insanebadpc.insanemarket.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cz.insanebadpc.insanemarket.R
import cz.insanebadpc.insanemarket.databinding.ItemAppBinding
import cz.insanebadpc.insanemarket.model.AppEntry
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

class AppAdapter(
    private val onAction: (AppEntry) -> Unit,
    private val onOpen: (AppEntry) -> Unit,
    private val onEdit: (AppEntry) -> Unit,
    private val onDetails: (AppEntry) -> Unit,
) : ListAdapter<AppEntry, AppAdapter.VH>(Diff) {
    object Diff : DiffUtil.ItemCallback<AppEntry>() {
        override fun areItemsTheSame(oldItem: AppEntry, newItem: AppEntry) = oldItem.repoFullName == newItem.repoFullName
        override fun areContentsTheSame(oldItem: AppEntry, newItem: AppEntry) = oldItem == newItem
    }

    class VH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.b
        b.name.text = item.displayName
        b.repo.text = listOfNotNull(item.repoFullName, item.language).joinToString(" • ")
        b.version.text = buildString {
            append(item.latestTag ?: item.latestName ?: "bez verze")
            item.assetSize?.let { append(" • ").append(formatBytes(it)) }
            item.installedVersion?.let { append(" • nainstalováno ").append(it) }
        }
        b.desc.text = item.description.lineSequence().firstOrNull()?.take(180).orEmpty().ifBlank { "Aplikace z GitHub release." }
        b.status.text = when {
            item.isDownloading -> "Stahuji ${item.downloadProgress}%…"
            item.hasUpdate -> "Dostupná aktualizace ${item.latestTag.orEmpty()}"
            item.isInstalled -> "Nainstalováno${item.installedVersion?.let { " ($it)" } ?: ""}"
            else -> "Připraveno ke stažení"
        }
        b.badge.visibility = if (item.hasUpdate || item.isInstalled) View.VISIBLE else View.GONE
        b.badge.text = if (item.hasUpdate) "UPDATE" else "INSTALLED"
        b.action.isEnabled = !item.isDownloading
        b.action.text = when {
            item.isDownloading -> "${item.downloadProgress}%"
            item.hasUpdate -> b.root.context.getString(R.string.update)
            item.isInstalled -> b.root.context.getString(R.string.reinstall)
            else -> b.root.context.getString(R.string.download_install)
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

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val group = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceAtMost(units.lastIndex)
        return DecimalFormat("#,##0.#").format(bytes / 1024.0.pow(group.toDouble())) + " " + units[group]
    }
}
