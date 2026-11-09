package cz.insanebadpc.insanemarket.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import cz.insanebadpc.insanemarket.R
import cz.insanebadpc.insanemarket.data.AppStateStore
import cz.insanebadpc.insanemarket.data.GitHubMarket
import cz.insanebadpc.insanemarket.databinding.ActivityMainBinding
import cz.insanebadpc.insanemarket.model.AppEntry
import cz.insanebadpc.insanemarket.util.ApkInstaller
import cz.insanebadpc.insanemarket.util.InstalledApps
import cz.insanebadpc.insanemarket.util.LocalOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private enum class Filter { ALL, UPDATES, INSTALLED }

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: AppAdapter
    private lateinit var overrides: LocalOverrides
    private lateinit var state: AppStateStore
    private lateinit var market: GitHubMarket
    private var allEntries = listOf<AppEntry>()
    private var filter = Filter.ALL
    private var query = ""
    private var pendingInstall: Pair<AppEntry, File>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        overrides = LocalOverrides(this)
        state = AppStateStore(this)
        market = GitHubMarket(owner = state.owner(), tokenProvider = { state.githubToken() })
        adapter = AppAdapter(::onAction, ::onOpen, ::onEdit, ::onDetails)
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter
        b.swipe.setOnRefreshListener { scan() }
        b.searchInput.doAfterTextChanged { query = it?.toString().orEmpty(); render() }
        b.filterGroup.setOnCheckedStateChangeListener { _, checked ->
            filter = when (checked.firstOrNull()) {
                R.id.filterUpdates -> Filter.UPDATES
                R.id.filterInstalled -> Filter.INSTALLED
                else -> Filter.ALL
            }
            render()
        }
        scan()
    }

    override fun onResume() {
        super.onResume()
        pendingInstall?.let { (entry, file) ->
            if (ApkInstaller.canInstall(this) && file.exists()) {
                pendingInstall = null
                launchInstall(entry, file)
            }
        }
        if (::market.isInitialized && allEntries.isNotEmpty()) refreshInstalledState()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.main_menu, menu); return true }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> { scan(); true }
        R.id.action_settings -> { showSettings(); true }
        R.id.action_about -> { showAbout(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun scan() {
        b.statusText.text = getString(R.string.scanning)
        b.progress.visibility = View.VISIBLE
        b.progress.isIndeterminate = true
        b.swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                val installed = withContext(Dispatchers.IO) { InstalledApps.byPackage(this@MainActivity) }
                allEntries = withContext(Dispatchers.IO) {
                    market.setOwner(state.owner())
                    val list = market.scan().toMutableList()
                    overrides.applyTo(list)
                    applyInstalledState(list, installed)
                    list
                }
                state.setLastScanTime(System.currentTimeMillis())
                render()
            } catch (e: Exception) {
                allEntries = emptyList(); render()
                val msg = friendlyError(e)
                showState(getString(R.string.error_title), msg)
                b.statusText.text = "Chyba načítání"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                if (e is GitHubMarket.ApiException && (e.code == 401 || e.code == 403)) showSettings()
            } finally {
                b.progress.visibility = View.GONE
                b.swipe.isRefreshing = false
            }
        }
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is GitHubMarket.ApiException -> e.message ?: "GitHub API chyba"
        else -> e.message ?: "Neznámá chyba"
    }

    private fun refreshInstalledState() {
        lifecycleScope.launch {
            val installed = withContext(Dispatchers.IO) { InstalledApps.byPackage(this@MainActivity) }
            val list = allEntries.map { it.copy() }
            applyInstalledState(list, installed)
            allEntries = list
            render()
        }
    }

    private fun applyInstalledState(list: MutableList<AppEntry>, installed: Map<String, InstalledApps.InstalledInfo>) {
        for (e in list) {
            val mapped = state.packageForRepo(e.repoFullName) ?: e.manifestPackage
            val pkg = InstalledApps.match(e.manifestPackage, mapped, e.repoName, installed)
            if (pkg != null) {
                e.packageName = pkg.packageName
                e.installedVersion = pkg.versionName ?: pkg.versionCode.toString()
                e.installedVersionCode = pkg.versionCode
                e.isInstalled = true
                state.setPackageForRepo(e.repoFullName, pkg.packageName)
            } else {
                e.packageName = mapped
                e.isInstalled = false
            }
            val seen = state.lastSeenTag(e.repoFullName)
            e.hasUpdate = if (e.isInstalled) !e.latestTag.isNullOrBlank() && seen != null && seen != e.latestTag else false
            if (seen == null && !e.latestTag.isNullOrBlank() && e.isInstalled) {
                state.markSeen(e.repoFullName, e.latestTag); e.hasUpdate = false
            }
        }
    }

    private fun render() {
        val visible = allEntries.filter { e ->
            e.matches(query) && when (filter) {
                Filter.ALL -> true
                Filter.UPDATES -> e.hasUpdate
                Filter.INSTALLED -> e.isInstalled
            }
        }
        adapter.submitList(visible)
        b.statusText.text = buildString {
            append("${visible.size}/${allEntries.size} aplikací")
            val t = state.lastScanTime()
            if (t > 0) append(" • ").append(SimpleDateFormat("d.M. HH:mm", Locale.getDefault()).format(Date(t)))
            if (state.githubToken() != null) append(" • token")
        }
        if (visible.isEmpty()) {
            val title = if (query.isNotBlank() || filter != Filter.ALL) "Nic nenalezeno" else getString(R.string.empty_title)
            val body = if (query.isNotBlank() || filter != Filter.ALL) "Změň hledání nebo filtr." else getString(R.string.empty_body)
            showState(title, body)
        } else b.emptyState.visibility = View.GONE
    }

    private fun showState(title: String, body: String) {
        b.emptyState.visibility = View.VISIBLE
        b.emptyTitle.text = title
        b.emptyBody.text = body
    }

    private fun onDetails(entry: AppEntry) {
        val view = layoutInflater.inflate(R.layout.dialog_app_detail, null)
        view.findViewById<TextView>(R.id.detailVersion).text = "${entry.displayName} • ${entry.latestTag ?: "bez verze"}"
        view.findViewById<TextView>(R.id.detailMeta).text = listOfNotNull(
            entry.repoFullName, entry.language, entry.assetName,
            entry.publishedAt?.take(10)?.let { "vydáno $it" },
            (entry.packageName ?: entry.manifestPackage)?.let { "balíček $it" }
        ).joinToString("\n")
        view.findViewById<TextView>(R.id.detailNotes).text = entry.releaseNotes?.trim().orEmpty().ifBlank { entry.description }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.details))
            .setView(view)
            .setPositiveButton(R.string.download_install) { _, _ -> onAction(entry) }
            .setNeutralButton(R.string.edit) { _, _ -> onEdit(entry) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onEdit(entry: AppEntry) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_app, null)
        val name = view.findViewById<TextInputEditText>(R.id.editName).apply { setText(entry.displayName) }
        val desc = view.findViewById<TextInputEditText>(R.id.editDesc).apply { setText(entry.description) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Upravit aplikaci")
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                overrides.set(entry.repoFullName, name.text?.toString()?.ifBlank { entry.displayName }, desc.text?.toString())
                val n = name.text?.toString()?.trim().orEmpty()
                val d = desc.text?.toString()?.trim().orEmpty()
                allEntries = allEntries.map { if (it.repoFullName == entry.repoFullName) it.copy(displayName = n.ifBlank { it.displayName }, description = d.ifBlank { it.description }) else it }
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onOpen(entry: AppEntry) {
        val pkg = entry.packageName ?: return
        if (!ApkInstaller.openInstalled(this, pkg)) Toast.makeText(this, "Aplikaci nelze otevřít.", Toast.LENGTH_SHORT).show()
    }

    private fun onAction(entry: AppEntry) {
        val url = entry.apkUrl ?: return
        updateEntry(entry.repoFullName) { it.copy(isDownloading = true, downloadProgress = 0, status = "Stahování") }
        b.statusText.text = "Stahuji ${entry.displayName}…"
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    ApkInstaller.download(this@MainActivity, url, entry.assetName ?: "${entry.repoName}-${entry.latestTag ?: "latest"}.apk", state.githubToken()) { p ->
                        runOnUiThread { updateEntry(entry.repoFullName) { cur -> cur.copy(isDownloading = true, downloadProgress = p) } }
                    }
                }
                val apkPackage = withContext(Dispatchers.IO) { ApkInstaller.packageNameFromApk(this@MainActivity, file) }
                if (!apkPackage.isNullOrBlank()) {
                    state.setPackageForRepo(entry.repoFullName, apkPackage)
                    updateEntry(entry.repoFullName) { it.copy(packageName = apkPackage) }
                }
                state.markSeen(entry.repoFullName, entry.latestTag)
                updateEntry(entry.repoFullName) { it.copy(isDownloading = false, downloadProgress = 100, hasUpdate = false, status = "Instalace") }
                launchInstall(entry, file)
                b.statusText.text = "Instalátor otevřen: ${entry.displayName}"
            } catch (e: Exception) {
                updateEntry(entry.repoFullName) { it.copy(isDownloading = false, downloadProgress = 0) }
                b.statusText.text = "Stažení selhalo"
                Toast.makeText(this@MainActivity, e.message ?: "Chyba", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchInstall(entry: AppEntry, file: File) {
        if (ApkInstaller.install(this, file)) return
        pendingInstall = entry to file
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.install_permission_title)
            .setMessage(R.string.install_permission_body)
            .setPositiveButton(R.string.open_settings) { _, _ -> ApkInstaller.requestInstallPermission(this) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateEntry(repoFullName: String, transform: (AppEntry) -> AppEntry) {
        allEntries = allEntries.map { if (it.repoFullName == repoFullName) transform(it) else it }
        render()
    }

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val owner = view.findViewById<TextInputEditText>(R.id.editOwner).apply { setText(state.owner()) }
        val token = view.findViewById<TextInputEditText>(R.id.editToken).apply { setText(state.githubToken().orEmpty()) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                state.setOwner(owner.text?.toString().orEmpty())
                state.setGithubToken(token.text?.toString())
                market.setOwner(state.owner())
                scan()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about)
            .setMessage(getString(R.string.about_body) + "\n\nÚčet: ${state.owner()}\nToken: ${if (state.githubToken() != null) "nastaven" else "nenastaven"}")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
