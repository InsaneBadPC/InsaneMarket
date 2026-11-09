package cz.insanebadpc.insanemarket.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: AppAdapter
    private lateinit var overrides: LocalOverrides
    private lateinit var state: AppStateStore
    private val market by lazy { GitHubMarket(tokenProvider = { state.githubToken() }) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        overrides = LocalOverrides(this)
        state = AppStateStore(this)
        adapter = AppAdapter(::onAction, ::onEdit)
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter
        b.refreshButton.setOnClickListener { scan() }
        b.swipe.setOnRefreshListener { scan() }
        scan()
    }

    private fun scan() {
        b.statusText.text = getString(R.string.scanning)
        b.swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                val installed = withContext(Dispatchers.IO) { InstalledApps.byPackage(this@MainActivity) }
                val entries = withContext(Dispatchers.IO) {
                    val list = market.scan().toMutableList()
                    overrides.applyTo(list)
                    for (e in list) {
                        val pkg = InstalledApps.guessPackage(e.repoName, installed)
                        if (pkg != null) {
                            e.packageName = pkg.packageName
                            e.installedVersion = pkg.versionName ?: pkg.versionCode.toString()
                            e.installedVersionCode = pkg.versionCode
                            val seen = state.lastSeenTag(e.repoFullName)
                            e.hasUpdate = !e.latestTag.isNullOrBlank() && seen != e.latestTag
                        } else if (!e.latestTag.isNullOrBlank() && state.lastSeenTag(e.repoFullName) != e.latestTag) {
                            e.hasUpdate = true
                        }
                    }
                    list
                }
                adapter.submit(entries)
                b.statusText.text = if (entries.isEmpty()) "Žádné aplikace s APK release." else "Moje aplikace: ${entries.size}"
            } catch (e: Exception) {
                b.statusText.text = "Chyba: ${e.message}"
                Toast.makeText(this@MainActivity, e.message ?: "Chyba", Toast.LENGTH_LONG).show()
            } finally {
                b.swipe.isRefreshing = false
            }
        }
    }

    private fun onEdit(entry: AppEntry) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_app, null)
        val name = view.findViewById<EditText>(R.id.editName).apply { setText(entry.displayName) }
        val desc = view.findViewById<EditText>(R.id.editDesc).apply { setText(entry.description) }
        AlertDialog.Builder(this)
            .setTitle("Upravit ${entry.repoName}")
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                overrides.set(entry.repoFullName, name.text.toString().ifBlank { entry.displayName }, desc.text.toString())
                scan()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onAction(entry: AppEntry) {
        val url = entry.apkUrl ?: return
        b.statusText.text = "Stahuji ${entry.displayName}…"
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    ApkInstaller.download(this@MainActivity, url, entry.assetName ?: "${entry.repoName}-${entry.latestTag ?: "latest"}.apk", state.githubToken())
                }
                state.markSeen(entry.repoFullName, entry.latestTag)
                ApkInstaller.install(this@MainActivity, file)
                b.statusText.text = "Instalační balíček připraven: ${entry.displayName}"
            } catch (e: Exception) {
                b.statusText.text = "Stažení selhalo: ${e.message}"
                Toast.makeText(this@MainActivity, e.message ?: "Chyba", Toast.LENGTH_LONG).show()
            }
        }
    }
}
