package cz.insanebadpc.insanemarket.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import cz.insanebadpc.insanemarket.BuildConfig
import cz.insanebadpc.insanemarket.R
import cz.insanebadpc.insanemarket.databinding.ActivityMainBinding
import cz.insanebadpc.insanemarket.model.AppEntry
import cz.insanebadpc.insanemarket.model.RepoDetailedInfo
import cz.insanebadpc.insanemarket.model.RepoFileItem
import cz.insanebadpc.insanemarket.util.ApkInstaller
import cz.insanebadpc.insanemarket.util.ByteFormat
import cz.insanebadpc.insanemarket.util.IconLoader
import cz.insanebadpc.insanemarket.util.InstalledApps
import cz.insanebadpc.insanemarket.worker.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private enum class Filter { ALL, UPDATES, INSTALLED, HIDDEN }

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: AppAdapter
    private val viewModel: MarketViewModel by viewModels()

    private var query = ""
    private var section = MarketSection.APK
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

        adapter = AppAdapter(
            currentSection = { section },
            onAction = ::onAction,
            onOpen = ::onOpen,
            onOpenWeb = ::onOpenWeb,
            onRate = { entry, stars -> viewModel.rateEntry(entry, stars) },
            onToggleHide = ::onToggleHide,
            onEdit = ::onEdit,
            onDetails = ::onItemClicked,
        )
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter
        b.swipe.setOnRefreshListener { viewModel.scan(force = true) }

        b.searchInput.doAfterTextChanged {
            query = it?.toString().orEmpty()
            lastState?.let(::render)
        }

        b.sectionTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                section = when (tab?.position) {
                    1 -> MarketSection.WEB
                    2 -> MarketSection.EXTERNAL
                    else -> MarketSection.APK
                }
                updateFabLabel()
                lastState?.let(::render)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        b.filterGroup.setOnCheckedStateChangeListener { _, checked ->
            filter = when (checked.firstOrNull()) {
                R.id.filterUpdates -> Filter.UPDATES
                R.id.filterInstalled -> Filter.INSTALLED
                R.id.filterHidden -> Filter.HIDDEN
                else -> Filter.ALL
            }
            lastState?.let(::render)
        }

        b.fabAdd.setOnClickListener { showAddUrlDialog(section) }
        b.emptyAddBtn.setOnClickListener { showAddUrlDialog(section) }

        if (savedInstanceState != null) {
            query = savedInstanceState.getString(KEY_QUERY).orEmpty()
            section = MarketSection.values().getOrElse(savedInstanceState.getInt(KEY_SECTION, 0)) { MarketSection.APK }
            filter = Filter.values().getOrElse(savedInstanceState.getInt(KEY_FILTER, 0)) { Filter.ALL }
            pendingInstallRepo = savedInstanceState.getString(KEY_PENDING_INSTALL)
            if (query.isNotEmpty()) b.searchInput.setText(query)
            b.sectionTabs.getTabAt(section.ordinal)?.select()
            when (filter) {
                Filter.ALL -> b.filterGroup.check(R.id.filterAll)
                Filter.UPDATES -> b.filterGroup.check(R.id.filterUpdates)
                Filter.INSTALLED -> b.filterGroup.check(R.id.filterInstalled)
                Filter.HIDDEN -> b.filterGroup.check(R.id.filterHidden)
            }
        }
        updateFabLabel()

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_QUERY, query)
        outState.putInt(KEY_SECTION, section.ordinal)
        outState.putInt(KEY_FILTER, filter.ordinal)
        outState.putString(KEY_PENDING_INSTALL, pendingInstallRepo)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_add -> {
            showAddUrlDialog(section)
            true
        }

        R.id.action_refresh -> {
            viewModel.scan(force = true)
            true
        }

        R.id.action_unhide_all -> {
            viewModel.unhideAll()
            Toast.makeText(this, "Všechny skryté položky byly obnoveny.", Toast.LENGTH_SHORT).show()
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

    private fun updateFabLabel() {
        b.fabAdd.text = when (section) {
            MarketSection.APK -> "+ Přidat APK / Repo"
            MarketSection.WEB -> "+ Přidat Web App"
            MarketSection.EXTERNAL -> "+ Přidat Cizí Repo"
        }
    }

    private fun matchesSection(entry: AppEntry, targetSection: MarketSection): Boolean = when (targetSection) {
        MarketSection.APK -> !entry.isExternal && entry.hasApk
        MarketSection.WEB -> !entry.isExternal && entry.hasWebApp
        MarketSection.EXTERNAL -> entry.isExternal
    }

    private fun render(state: MarketUiState) {
        lastState = state

        val apkCount = state.entries.count { !it.isHidden && matchesSection(it, MarketSection.APK) }
        val webCount = state.entries.count { !it.isHidden && matchesSection(it, MarketSection.WEB) }
        val extCount = state.entries.count { !it.isHidden && matchesSection(it, MarketSection.EXTERNAL) }
        val hiddenCount = state.entries.count { it.isHidden && matchesSection(it, section) }

        b.sectionTabs.getTabAt(0)?.text = "📱 APK ($apkCount)"
        b.sectionTabs.getTabAt(1)?.text = "🌐 Web App ($webCount)"
        b.sectionTabs.getTabAt(2)?.text = "📦 Cizí ($extCount)"
        b.filterHidden.text = if (hiddenCount > 0) "Skryté ($hiddenCount)" else getString(R.string.filter_hidden)

        val visible = state.entries
            .filter { entry ->
                matchesSection(entry, section) &&
                    entry.matches(query) &&
                    when (filter) {
                        Filter.ALL -> !entry.isHidden
                        Filter.UPDATES -> !entry.isHidden && entry.hasUpdate
                        Filter.INSTALLED -> !entry.isHidden && entry.isInstalled
                        Filter.HIDDEN -> entry.isHidden
                    }
            }
            .sortedWith(
                compareByDescending<AppEntry> { it.userRating }
                    .thenByDescending { it.hasUpdate }
                    .thenByDescending { it.publishedAt ?: "" }
                    .thenBy { it.displayName.lowercase() }
            )

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
                section == MarketSection.EXTERNAL -> showState(
                    "Žádné cizí repozitáře",
                    "Přidej libovolný GitHub repozitář klepnutím na „+ Přidat Cizí Repo“ a prozkoumej jeho soubory, vydání a Pages.",
                )
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
        append(getString(R.string.status_count, visibleCount))
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
        if (!entry.hasApk) {
            if (entry.hasWebApp) onOpenWeb(entry) else onItemClicked(entry)
            return
        }
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

    private fun onOpenWeb(entry: AppEntry) {
        val url = entry.webUrl?.takeIf { it.isNotBlank() } ?: entry.repoUrl
        openBrowserUrl(url)
    }

    private fun openBrowserUrl(url: String) {
        if (url.isBlank()) return
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.cannot_open), Toast.LENGTH_SHORT).show()
        }
    }

    private fun onToggleHide(entry: AppEntry) {
        val nextHidden = !entry.isHidden
        viewModel.setHidden(entry, nextHidden)
        val msg = if (nextHidden) {
            "Položka „${entry.displayName}“ byla skryta (najdeš ji ve filtru Skryté)."
        } else {
            "Položka „${entry.displayName}“ byla obnovena."
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun onItemClicked(entry: AppEntry) {
        if (section == MarketSection.EXTERNAL || entry.isExternal) {
            showRepoExplorer(entry)
        } else {
            onDetails(entry)
        }
    }

    private fun onDetails(entry: AppEntry) {
        val view = layoutInflater.inflate(R.layout.dialog_app_detail, null)
        val icon = view.findViewById<ImageView>(R.id.detailIcon)
        IconLoader.bind(icon, entry)

        view.findViewById<TextView>(R.id.detailVersion).text =
            "${entry.displayName} • ${entry.latestTag ?: getString(R.string.no_version)}"
        view.findViewById<TextView>(R.id.detailMeta).text = listOfNotNull(
            entry.repoFullName.removePrefix("manual:"),
            entry.language,
            if (entry.userRating > 0) "Hodnocení: ${"★".repeat(entry.userRating)}${"☆".repeat(5 - entry.userRating)}" else "Nehodnoceno",
            entry.assetName,
            entry.assetSize?.let { ByteFormat.bytes(it) },
            entry.webUrl?.let { "Web: $it" },
            entry.publishedAt?.take(10)?.let { getString(R.string.published_on, it) },
            entry.packageName?.let { getString(R.string.package_label, it) },
        ).joinToString("\n")
        view.findViewById<TextView>(R.id.detailNotes).text =
            entry.releaseNotes?.trim().orEmpty().ifBlank { entry.description }

        val openWebBtn = view.findViewById<MaterialButton>(R.id.detailOpenWeb)
        if (entry.hasWebApp) {
            openWebBtn.visibility = View.VISIBLE
            openWebBtn.setOnClickListener { onOpenWeb(entry) }
        } else {
            openWebBtn.visibility = View.GONE
        }

        val exploreBtn = view.findViewById<MaterialButton>(R.id.detailExploreRepo)
        val isGitHubRepo = !entry.repoFullName.startsWith("manual:") && entry.repoFullName.contains('/')
        exploreBtn.visibility = if (isGitHubRepo) View.VISIBLE else View.GONE

        val assign = view.findViewById<TextView>(R.id.detailAssign)
        assign.visibility = if (entry.isInstalled || !entry.hasApk) View.GONE else View.VISIBLE
        assign.setOnClickListener { showInstalledPicker(entry) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.details)
            .setView(view)
            .setPositiveButton(if (entry.hasApk) actionLabel(entry) else getString(R.string.open_web)) { _, _ ->
                if (entry.hasApk) onAction(entry) else onOpenWeb(entry)
            }
            .setNeutralButton(R.string.edit) { _, _ -> onEdit(entry) }
            .setNegativeButton(R.string.cancel, null)
            .create()

        exploreBtn.setOnClickListener {
            dialog.dismiss()
            showRepoExplorer(entry)
        }
        dialog.show()
    }

    private fun showRepoExplorer(entry: AppEntry) {
        if (entry.repoFullName.startsWith("manual:") || !entry.repoFullName.contains('/')) {
            openBrowserUrl(entry.repoUrl)
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_repo_explorer, null)
        val icon = view.findViewById<ImageView>(R.id.explorerIcon)
        val title = view.findViewById<TextView>(R.id.explorerTitle)
        val subtitle = view.findViewById<TextView>(R.id.explorerSubtitle)
        val progress = view.findViewById<View>(R.id.explorerProgress)
        val tabs = view.findViewById<TabLayout>(R.id.explorerTabs)
        val container = view.findViewById<LinearLayout>(R.id.explorerContentContainer)

        IconLoader.bind(icon, entry)
        title.text = entry.displayName
        subtitle.text = entry.repoFullName

        var loadedInfo: RepoDetailedInfo? = null
        var currentDirPath = ""
        var currentFiles: List<RepoFileItem>? = null

        fun renderOverview(info: RepoDetailedInfo?) {
            container.removeAllViews()
            if (info == null) {
                addInfoLine(container, "Načítám podrobnosti repozitáře…")
                return
            }

            addSectionTitle(container, "Informace o repozitáři")
            addInfoLine(container, info.description)
            addInfoLine(
                container,
                buildList {
                    add("Autor: ${info.ownerLogin}")
                    info.language?.let { add("Jazyk: $it") }
                    add("⭐ ${info.starsCount} hvězdiček")
                    add("🍴 ${info.forksCount} forků")
                    add("🌿 Větev: ${info.defaultBranch}")
                    if (info.isPrivate) add("🔒 Soukromé repo") else add("🌍 Veřejné repo")
                    info.updatedAt?.take(10)?.let { add("Aktualizováno: $it") }
                }.joinToString(" • ")
            )

            if (info.topics.isNotEmpty()) {
                addInfoLine(container, "Témata: ${info.topics.joinToString(", ")}")
            }

            // Action row inside overview
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
            }
            val ghBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "GitHub"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { openBrowserUrl(info.htmlUrl) }
            }
            btnRow.addView(ghBtn)

            if (!info.pagesUrl.isNullOrBlank()) {
                val pagesBtn = MaterialButton(this).apply {
                    text = "🌐 Otevřít Pages / Web"
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(8)
                    }
                    setOnClickListener { openBrowserUrl(info.pagesUrl) }
                }
                btnRow.addView(pagesBtn)
            }
            container.addView(btnRow)

            addSectionTitle(container, "README")
            addInfoLine(
                container,
                info.readmeText?.trim()?.take(4000)?.ifBlank { "README není k dispozici." }
                    ?: "README v tomto repozitáři nebylo nalezeno.",
            )
        }

        fun loadAndRenderDirectory(path: String) {
            currentDirPath = path
            progress.visibility = View.VISIBLE
            container.removeAllViews()
            addInfoLine(container, "Načítám složku /${path}…")
            lifecycleScope.launch {
                try {
                    val branch = loadedInfo?.defaultBranch ?: entry.defaultBranch
                    val files = viewModel.fetchRepoDirectory(entry.repoFullName, path, branch)
                    currentFiles = files
                    progress.visibility = View.GONE
                    container.removeAllViews()

                    addSectionTitle(container, if (path.isBlank()) "📁 Kořen repozitáře (/)" else "📁 /$path")

                    if (path.isNotBlank()) {
                        val parent = path.substringBeforeLast('/', "")
                        val upBtn = MaterialButton(
                            this@MainActivity,
                            null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle,
                        ).apply {
                            text = "⬅ O úroveň výš"
                            setOnClickListener { loadAndRenderDirectory(parent) }
                        }
                        container.addView(upBtn)
                    }

                    if (files.isEmpty()) {
                        addInfoLine(container, "Tato složka je prázdná.")
                        return@launch
                    }

                    for (file in files) {
                        val row = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(10), dp(10), dp(10), dp(10))
                            setBackgroundResource(R.drawable.status_badge)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = dp(6) }
                        }
                        val label = TextView(this@MainActivity).apply {
                            val iconEmoji = when {
                                file.isDir -> "📁"
                                file.isApk -> "📱"
                                else -> "📄"
                            }
                            val sizeStr = if (!file.isDir && file.size > 0) " (${ByteFormat.bytes(file.size)})" else ""
                            text = "$iconEmoji  ${file.name}$sizeStr"
                            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.market_text))
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        row.addView(label)

                        val actionHint = TextView(this@MainActivity).apply {
                            text = when {
                                file.isDir -> "Otevřít ➔"
                                file.isApk -> "Stáhnout APK"
                                else -> "Zobrazit"
                            }
                            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.market_primary))
                            textSize = 12f
                            setTypeface(typeface, Typeface.BOLD)
                        }
                        row.addView(actionHint)

                        row.setOnClickListener {
                            when {
                                file.isDir -> loadAndRenderDirectory(file.path)
                                file.isApk && !file.downloadUrl.isNullOrBlank() -> {
                                    val tempEntry = entry.copy(
                                        apkUrl = file.downloadUrl,
                                        assetName = file.name,
                                        assetSize = file.size,
                                    )
                                    requestDownload(tempEntry)
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Stahuji ${file.name}…",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                !file.htmlUrl.isNullOrBlank() -> openBrowserUrl(file.htmlUrl)
                                !file.downloadUrl.isNullOrBlank() -> openBrowserUrl(file.downloadUrl)
                            }
                        }
                        container.addView(row)
                    }
                } catch (e: Exception) {
                    progress.visibility = View.GONE
                    container.removeAllViews()
                    addInfoLine(container, "Nepodařilo se načíst soubory: ${e.message}")
                }
            }
        }

        fun renderReleases(info: RepoDetailedInfo?) {
            container.removeAllViews()
            val releases = info?.releases.orEmpty()
            if (releases.isEmpty()) {
                addInfoLine(container, "Tento repozitář zatím nemá žádná publikovaná vydání (Releases).")
                return
            }
            for (rel in releases) {
                addSectionTitle(
                    container,
                    buildString {
                        append("🚀 ").append(rel.name.ifBlank { rel.tagName })
                        if (rel.prerelease) append(" (Pre-release)")
                        rel.publishedAt?.take(10)?.let { append(" • ").append(it) }
                    }
                )
                if (!rel.body.isNullOrBlank()) {
                    addInfoLine(container, rel.body.take(800))
                }
                if (rel.assets.isEmpty()) {
                    addInfoLine(container, "Žádné přiložené soubory v tomto vydání.")
                } else {
                    for (asset in rel.assets) {
                        val btn = MaterialButton(
                            this,
                            null,
                            if (asset.isApk) com.google.android.material.R.attr.materialButtonStyle
                            else com.google.android.material.R.attr.materialButtonOutlinedStyle,
                        ).apply {
                            val prefix = if (asset.isApk) "📱 Stáhnout a instalovat: " else "⬇ Soubor: "
                            text = "$prefix${asset.name} (${ByteFormat.bytes(asset.size)})"
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = dp(6) }
                            setOnClickListener {
                                if (asset.isApk) {
                                    val tempEntry = entry.copy(
                                        latestTag = rel.tagName,
                                        apkUrl = asset.downloadUrl,
                                        apkApiUrl = asset.apiUrl,
                                        assetName = asset.name,
                                        assetSize = asset.size,
                                        assetDigest = asset.digest,
                                    )
                                    requestDownload(tempEntry)
                                    Toast.makeText(this@MainActivity, "Stahuji ${asset.name}…", Toast.LENGTH_SHORT).show()
                                } else {
                                    openBrowserUrl(asset.downloadUrl)
                                }
                            }
                        }
                        container.addView(btn)
                    }
                }
            }
        }

        fun renderPages(info: RepoDetailedInfo?) {
            container.removeAllViews()
            addSectionTitle(container, "🌐 GitHub Pages & Web App")
            val webUrl = entry.webUrl ?: info?.pagesUrl ?: info?.homepageUrl
            if (!webUrl.isNullOrBlank()) {
                addInfoLine(container, "Aktivní adresa webové aplikace / Pages:\n$webUrl")
                val openBtn = MaterialButton(this).apply {
                    text = "🌐 Otevřít Web App v prohlížeči"
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) }
                    setOnClickListener { openBrowserUrl(webUrl) }
                }
                container.addView(openBtn)
            } else {
                addInfoLine(container, "Pro tento repozitář zatím není detekována aktivní GitHub Pages nebo Web App URL.")
            }
        }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> renderOverview(loadedInfo)
                    1 -> loadAndRenderDirectory(currentDirPath)
                    2 -> renderReleases(loadedInfo)
                    3 -> renderPages(loadedInfo)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        renderOverview(null)

        lifecycleScope.launch {
            try {
                val details = viewModel.fetchRepoDetails(entry.repoFullName)
                loadedInfo = details
                progress.visibility = View.GONE
                subtitle.text = "${details.fullName} • ⭐ ${details.starsCount} • ${details.releases.size} vydání"
                tabs.getTabAt(2)?.text = "Vydání (${details.releases.size})"
                if (tabs.selectedTabPosition == 0) renderOverview(details)
            } catch (e: Exception) {
                progress.visibility = View.GONE
                container.removeAllViews()
                addInfoLine(container, "Chyba při načítání detailů repozitáře: ${e.message}")
            }
        }

        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton("GitHub") { _, _ -> openBrowserUrl(entry.repoUrl) }
            .setNeutralButton(R.string.edit) { _, _ -> onEdit(entry) }
            .setNegativeButton("Zavřít", null)
            .show()
    }

    private fun addSectionTitle(parent: LinearLayout, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.market_text))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        parent.addView(tv)
    }

    private fun addInfoLine(parent: LinearLayout, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.market_muted))
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        parent.addView(tv)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showAddUrlDialog(targetSection: MarketSection) {
        val view = layoutInflater.inflate(R.layout.dialog_add_url, null)
        val hint = view.findViewById<TextView>(R.id.addDialogHint)
        val inputUrl = view.findViewById<TextInputEditText>(R.id.inputUrl)
        val inputName = view.findViewById<TextInputEditText>(R.id.inputName)
        val inputDesc = view.findViewById<TextInputEditText>(R.id.inputDesc)
        val inputWebUrlLayout = view.findViewById<TextInputLayout>(R.id.inputWebUrlLayout)
        val inputWebUrl = view.findViewById<TextInputEditText>(R.id.inputWebUrl)
        val inputIconUrl = view.findViewById<TextInputEditText>(R.id.inputIconUrl)

        val dialogTitle = when (targetSection) {
            MarketSection.APK -> {
                hint.text = "Zadej URL GitHub repozitáře (např. https://github.com/uzivatel/repo) nebo přímý odkaz na .apk soubor."
                getString(R.string.add_apk_title)
            }
            MarketSection.WEB -> {
                hint.text = "Zadej URL webové aplikace (např. https://moje-appka.vercel.app) nebo URL GitHub repozitáře."
                inputWebUrlLayout.visibility = View.GONE
                getString(R.string.add_web_title)
            }
            MarketSection.EXTERNAL -> {
                hint.text = "Zadej URL cizího GitHub repozitáře (např. https://github.com/owner/repo nebo owner/repo). Automaticky se načte název, popis, soubory, vydání i Pages."
                inputWebUrlLayout.visibility = View.GONE
                getString(R.string.add_external_title)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(dialogTitle)
            .setView(view)
            .setPositiveButton("Přidat") { _, _ ->
                val rawUrl = inputUrl.text?.toString().orEmpty()
                Toast.makeText(this, "Načítám a přidávám položku…", Toast.LENGTH_SHORT).show()
                viewModel.addByUrl(
                    section = targetSection,
                    rawUrl = rawUrl,
                    customName = inputName.text?.toString(),
                    customDesc = inputDesc.text?.toString(),
                    customWebUrl = inputWebUrl.text?.toString(),
                    customIconUrl = inputIconUrl.text?.toString(),
                ) { result ->
                    result.onSuccess { added ->
                        Toast.makeText(
                            this,
                            "Položka „${added.displayName}“ byla přidána.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }.onFailure { err ->
                        Toast.makeText(
                            this,
                            err.message ?: "Nepodařilo se přidat položku.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
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
        val webUrl = view.findViewById<TextInputEditText>(R.id.editWebUrl).apply { setText(entry.webUrl.orEmpty()) }
        val apkUrl = view.findViewById<TextInputEditText>(R.id.editApkUrl).apply { setText(entry.apkUrl.orEmpty()) }
        val iconUrl = view.findViewById<TextInputEditText>(R.id.editIconUrl).apply { setText(entry.iconUrl.orEmpty()) }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.saveOverride(
                    repoFullName = entry.repoFullName,
                    displayName = name.text?.toString(),
                    description = desc.text?.toString(),
                    webUrl = webUrl.text?.toString(),
                    apkUrl = apkUrl.text?.toString(),
                    iconUrl = iconUrl.text?.toString(),
                )
            }
            .setNegativeButton(R.string.cancel, null)

        if (entry.isExternal || entry.isManual) {
            builder.setNeutralButton(R.string.delete_item) { _, _ ->
                viewModel.removeEntry(entry)
                Toast.makeText(this, "Položka byla odebrána.", Toast.LENGTH_SHORT).show()
            }
        } else {
            builder.setNeutralButton(if (entry.isHidden) R.string.unhide else R.string.hide) { _, _ ->
                onToggleHide(entry)
            }
        }
        builder.show()
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

    private fun handleInstallIntent(intent: Intent?) {
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
        private const val KEY_SECTION = "section"
        private const val KEY_FILTER = "filter"
        private const val KEY_PENDING_INSTALL = "pending_install"
    }
}
