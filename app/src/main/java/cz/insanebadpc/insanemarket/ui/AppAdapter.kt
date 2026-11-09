package cz.insanebadpc.insanemarket.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cz.insanebadpc.insanemarket.databinding.ItemAppBinding
import cz.insanebadpc.insanemarket.model.AppEntry

class AppAdapter(
    private val onAction: (AppEntry) -> Unit,
    private val onEdit: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppAdapter.VH>() {
    private val items = mutableListOf<AppEntry>()

    fun submit(list: List<AppEntry>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

    class VH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.b.name.text = item.displayName
        holder.b.repo.text = item.repoFullName + (item.language?.let { " • $it" } ?: "")
        holder.b.version.text = buildString {
            append(item.latestTag ?: "bez verze")
            item.installedVersion?.let { append(" • nainstalováno $it") }
            item.packageName?.let { append(" • $it") }
        }
        holder.b.desc.text = item.description
        holder.b.status.text = when {
            item.apkUrl == null -> "Repozitář nemá APK v latest release."
            item.hasUpdate -> "Nová verze dostupná."
            item.installedVersion != null -> "Nainstalováno."
            else -> "Připraveno k instalaci."
        }
        holder.b.action.isEnabled = item.apkUrl != null
        holder.b.action.text = when {
            item.apkUrl == null -> "Bez APK"
            item.hasUpdate -> "Aktualizovat"
            item.installedVersion != null -> "Reinstalovat"
            else -> "Instalovat"
        }
        holder.b.action.setOnClickListener { onAction(item) }
        holder.b.edit.setOnClickListener { onEdit(item) }
    }
}
