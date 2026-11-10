package cz.insanebadpc.insanemarket.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import cz.insanebadpc.insanemarket.BuildConfig
import cz.insanebadpc.insanemarket.R
import cz.insanebadpc.insanemarket.databinding.ActivityMainBinding
import cz.insanebadpc.insanemarket.model.AppEntry
import cz.insanebadpc.insanemarket.util.ApkInstaller
import cz.insanebadpc.insanemarket.util.ByteFormat
import cz.insanebadpc.insanemarket.util.InstalledApps
import cz.insanebadpc.insanemarket.worker.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private enum class Filter { ALL, UPDATES, INSTALLED }

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: AppAdapter
    private val viewModel: MarketViewModel by viewModels()

    private var query = ""
    private var filter = Filter.ALL
    private var lastState: MarketUiState? = null
    private var lastShownError: String? = null
    private var pendingInstallRepo: String? = null
    private var pendingDownload: AppEntry? = null
    private var permissionPromptedFor: String? = null
    private var askedNotifications = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingDownload?.let { viewModel.startDownload(it) }
        pendingDownload = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        adapter = AppAdapter(::onAction, ::onOpen, ::onEdit, ::onDetails)
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter
        b.swipe.setOnRefreshListener { viewModel.scan(force = true) }

        b.searchInput.doAfterTextChanged {
            query = it?.toString().orEmpty()
            lastState?.let(::render)
        }
        b.filterGroup.setOnCheckedStateChangeListener { _, checked ->
            filter = when (checked.firstOrNull()) {
                R.id.filterUpdates -> Filter.UPDATES
                R.id.filterInstalled -> Filter.INSTALLED
                else -> Filter.ALL
            }
            lastState?.let(::render)
        }

        if (savedInstanceState != null) {
            query = savedInstanceState.getString(KEY_QUERY).orEmpty()
            filter = Filter.values().getOrElse(savedInstanceState.getInt(KEY_FILTER, 0)) { Filter.ALL }
            pendingInstallRepo = savedInstanceState.getString(KEY_PENDING_INSTALL)
            if (query.isNotEmpty()) b.searchInput.setText(query)
            when (filter) {
                Filter.ALL -> b.filterGroup.check(R.id.filterAll)
                Filter.UPDATES -> b.filterGroup.check(R.id.filterUpdates)
                Filter.INSTALLED -> b.filterGroup.check(R.id.filterInstalled)
            }
        }

        handleInstallIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshInstalled()
        maybeInstallPending()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_QUERY, query)
        outState.putInt(KEY_FILTER, filter.ordinal)
        outState.putString(KEY_PENDING_INSTALL, pendingInstallRepo)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            viewModel.scan(force = true)
            true
        }

        R.id.action_settings -> {
            showSettings()
            true
        }

        R.id.action_about -> {
            showAbout()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun render(state: MarketUiState) {
        lastState = state
        val visible = state.entries.filter { entry ->
            entry.matches(query) && when (filter) {
                Filter.ALL -> true
                Filter.UPDATES -> entry.hasUpdate
                Filter.INSTALLED -> entry.isInstalled
            }
        }
        adapter.submitList(visible)
        b.statusText.text = buildStatus(state, visible.size)
        b.progress.visibility = if (state.scanning) View.VISIBLE else View.GONE
        b.progress.isIndeterminate = true
        if (!state.scanning) b.swipe.isRefreshing = false

        if (visible.isEmpty()) {
            val filtered = query.isNotBlank() || filter != Filter.ALL
            when {
                state.error != null && !filtered -> showState(getString(R.string.error_title), state.error)
                filtered -> showState(getString(R.string.nothing_found), getString(R.string.nothing_found_body))
                else -> showState(getString(R.string.empty_title), getString(R.string.empty_body))
            }
        } else {
            b.emptyState.visibility = View.GONE
        }

        if (state.error != null && state.error != lastShownError) {
            lastShownError = state.error
            Toast.makeText(this, state.error, Toast.LENGTH_LONG).show()
            if (state.errorNeedsSettings) showSettings()
        } else if (state.error == null) {
            lastShownError = null
        }

        maybeInstallPending()
    }

    private fun buildStatus(state: MarketUiState, visibleCount: Int): String = buildString {
        if (state.scanning) append(getString(R.string.scanning)).append(" • ")
        append(getString(R.string.status_count, visibleCount, state.entries.size))
        if (state.lastScan > 0L) {
            append(" • ").append(formatTime(state.lastScan))
        }
        if (state.rateRemaining != null) {
            append(" • ").append(getString(R.string.status_rate, state.rateRemaining))
            if (state.rateRemaining == 0 && state.rateResetEpoch != null) {
                append(" (").append(formatTime(state.rateResetEpoch * 1000)).append(")")
            }
        }
        if (state.fromCache) append(" • ").append(getString(R.string.status_cache))
        if (state.failedRepos > 0) {
            append(" • ").append(getString(R.string.status_failed_repos, state.failedRepos))
        }
    }

    private fun formatTime(epochMillis: Long): String =
        SimpleDateFormat("d.M. HH:mm", Locale.getDefault()).format(Date(epochMillis))

    private fun showState(title: String, body: String) {
        b.emptyState.visibility = View.VISIBLE
        b.emptyTitle.text = title
        b.emptyBody.text = body
    }

    private fun onAction(entry: AppEntry) {
        if (entry.isDownloading) {
            viewModel.cancelDownload(entry)
            return
        }
        val file = viewModel.downloadedFile(entry.repoFullName)
        if (entry.downloadReady && file != null) {
            pendingInstallRepo = entry.repoFullName
            maybeInstallPending()
            return
        }
        requestDownload(entry)
    }

    private fun requestDownload(entry: AppEntry) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !askedNotifications
        ) {
            askedNotifications = true
            pendingDownload = entry
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startDownload(entry)
        }
    }

    private fun onOpen(entry: AppEntry) {
        val packageName = entry.packageName ?: return
        if (!ApkInstaller.openInstalled(this, packageName)) {
            Toast.makeText(this, getString(R.string.cannot_open), Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDetails(entry: AppEntry) {
        val view = layoutInflater.inflate(R.layout.dialog_app_detail, null)
        view.findViewById<TextView>(R.id.detailVersion).text =
            "${entry.displayName} • ${entry.latestTag ?: getString(R.string.no_version)}"
        view.findViewById<TextView>(R.id.detailMeta).text = listOfNotNull(
            entry.repoFullName,
            entry.language,
            entry.assetName,
            entry.assetSize?.let { ByteFormat.bytes(it) },
            entry.publishedAt?.take(10)?.let { getString(R.string.published_on, it) },
            entry.packageName?.let { getString(R.string.package_label, it) },
        ).joinToString("\n")
        view.findViewById<TextView>(R.id.detailNotes).text =
            entry.releaseNotes?.trim().orEmpty().ifBlank { entry.description }

        val assign = view.findViewById<TextView>(R.id.detailAssign)
        assign.visibility = if (entry.isInstalled) View.GONE else View.VISIBLE
        assign.setOnClickListener { showInstalledPicker(entry) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.details)
            .setView(view)
            .setPositiveButton(actionLabel(entry)) { _, _ -> onAction(entry) }
            .setNeutralButton(R.string.edit) { _, _ -> onEdit(entry) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun actionLabel(entry: AppEntry): String = when {
        entry.isDownloading -> getString(R.string.cancel)
        entry.downloadReady -> getString(R.string.install)
        entry.hasUpdate -> getString(R.string.update)
        entry.isInstalled -> getString(R.string.reinstall)
        else -> getString(R.string.download_install)
    }

    private fun showInstalledPicker(entry: AppEntry) {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { InstalledApps.launchable(this@MainActivity) }
            if (apps.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.no_installed_apps, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = apps.map { "${it.label}\n${it.packageName}" }.toTypedArray()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.map_package_title)
                .setItems(labels) { _, which ->
                    viewModel.assignPackage(entry, apps[which].packageName)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun onEdit(entry: AppEntry) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_app, null)
        val name = view.findViewById<TextInputEditText>(R.id.editName).apply { setText(entry.displayName) }
        val desc = view.findViewById<TextInputEditText>(R.id.editDesc).apply { setText(entry.description) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.saveOverride(
                    entry.repoFullName,
                    name.text?.toString(),
                    desc.text?.toString(),
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val owner = view.findViewById<TextInputEditText>(R.id.editOwner)
            .apply { setText(viewModel.currentOwner()) }
        val token = view.findViewById<TextInputEditText>(R.id.editToken)
            .apply { setText(viewModel.currentToken().orEmpty()) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.updateSettings(
                    owner.text?.toString().orEmpty(),
                    token.text?.toString(),
                )
            }
            .setNeutralButton(R.string.clear_token) { _, _ ->
                token.setText("")
                viewModel.updateSettings(owner.text?.toString().orEmpty(), null)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about)
            .setMessage(
                getString(R.string.about_body) +
                    "\n\n" + getString(R.string.about_version, BuildConfig.VERSION_NAME) +
                    "\n" + getString(R.string.about_owner, viewModel.currentOwner()) +
                    "\n" + getString(
                        R.string.about_token,
                        if (viewModel.currentToken() != null) getString(R.string.about_token_set)
                        else getString(R.string.about_token_missing),
                    )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun handleInstallIntent(intent: android.content.Intent?) {
        val safeIntent = intent ?: return
        val repo = safeIntent.getStringExtra(Downloads.EXTRA_INSTALL_REPO) ?: return
        safeIntent.removeExtra(Downloads.EXTRA_INSTALL_REPO)
        pendingInstallRepo = repo
        maybeInstallPending()
    }

    private fun maybeInstallPending() {
        val repo = pendingInstallRepo ?: return
        if (!ApkInstaller.canInstall(this)) {
            if (permissionPromptedFor != repo) {
                permissionPromptedFor = repo
                showInstallPermissionDialog()
            }
            return
        }
        permissionPromptedFor = null
        val file = viewModel.downloadedFile(repo)
        if (file == null) {
            pendingInstallRepo = null
            Toast.makeText(this, R.string.download_missing, Toast.LENGTH_LONG).show()
            return
        }
        pendingInstallRepo = null
        if (ApkInstaller.hasSignatureMismatch(this, file)) {
            Toast.makeText(this, R.string.signature_mismatch, Toast.LENGTH_LONG).show()
            return
        }
        if (!ApkInstaller.install(this, file)) {
            Toast.makeText(this, R.string.install_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showInstallPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.install_permission_title)
            .setMessage(R.string.install_permission_body)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                ApkInstaller.requestInstallPermission(this)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingInstallRepo = null
                permissionPromptedFor = null
            }
            .show()
    }

    companion object {
        private const val KEY_QUERY = "query"
        private const val KEY_FILTER = "filter"
        private const val KEY_PENDING_INSTALL = "pending_install"
    }
}
