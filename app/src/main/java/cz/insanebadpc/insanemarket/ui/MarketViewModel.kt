package cz.insanebadpc.insanemarket.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cz.insanebadpc.insanemarket.data.AppStateStore
import cz.insanebadpc.insanemarket.data.GitHubMarket
import cz.insanebadpc.insanemarket.data.ScanCache
import cz.insanebadpc.insanemarket.model.AppEntry
import cz.insanebadpc.insanemarket.util.InstalledApps
import cz.insanebadpc.insanemarket.util.InstalledInfo
import cz.insanebadpc.insanemarket.util.InstalledResolution
import cz.insanebadpc.insanemarket.util.LocalOverrides
import cz.insanebadpc.insanemarket.util.UpdateRules
import cz.insanebadpc.insanemarket.worker.Downloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class MarketUiState(
    val entries: List<AppEntry> = emptyList(),
    val scanning: Boolean = false,
    val error: String? = null,
    val errorNeedsSettings: Boolean = false,
    val rateRemaining: Int? = null,
    val rateResetEpoch: Long? = null,
    val fromCache: Boolean = false,
    val lastScan: Long = 0L,
    val failedRepos: Int = 0,
)

class MarketViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AppStateStore(application)
    private val overrides = LocalOverrides(application)
    private val cache = ScanCache(File(application.cacheDir, "market-cache.json"))
    private val market = GitHubMarket(store.owner(), { store.githubToken() }, cache)
    private val workManager = WorkManager.getInstance(application)

    private val _ui = MutableStateFlow(MarketUiState(lastScan = store.lastScanTime()))
    val ui: StateFlow<MarketUiState> = _ui.asStateFlow()

    private var scanJob: Job? = null

    init {
        observeDownloads()
        scan(force = false)
    }

    fun scan(force: Boolean = true) {
        if (scanJob?.isActive == true) return
        _ui.update { it.copy(scanning = true, error = null, errorNeedsSettings = false) }
        scanJob = viewModelScope.launch {
            try {
                market.setOwner(store.owner())
                val result = market.scan(force)
                val installed = withContext(Dispatchers.IO) { InstalledApps.byPackage(getApplication()) }
                val list = result.entries.toMutableList()
                overrides.applyTo(list)
                reconcile(list, installed)
                val now = System.currentTimeMillis()
                store.setLastScanTime(now)
                _ui.update {
                    it.copy(
                        entries = list,
                        scanning = false,
                        error = null,
                        errorNeedsSettings = false,
                        rateRemaining = result.rateRemaining,
                        rateResetEpoch = result.rateResetEpoch,
                        fromCache = result.fromCache,
                        lastScan = now,
                        failedRepos = result.failedRepos,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        scanning = false,
                        error = friendlyMessage(e),
                        errorNeedsSettings = needsSettings(e),
                    )
                }
            }
        }
    }

    fun refreshInstalled() {
        val current = _ui.value.entries
        if (current.isEmpty() || _ui.value.scanning) return
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { InstalledApps.byPackage(getApplication()) }
            val list = _ui.value.entries.map { it.copy() }.toMutableList()
            reconcile(list, installed)
            _ui.update { it.copy(entries = list) }
        }
    }

    fun startDownload(entry: AppEntry) {
        if (entry.apkUrl.isNullOrBlank()) return
        Downloads.enqueue(getApplication(), entry)
        updateEntry(entry.repoFullName) {
            it.copy(isDownloading = true, downloadProgress = 0, downloadReady = false, note = null)
        }
    }

    fun cancelDownload(entry: AppEntry) {
        Downloads.cancel(getApplication(), entry.repoFullName)
        updateEntry(entry.repoFullName) {
            it.copy(isDownloading = false, downloadProgress = 0)
        }
    }

    fun downloadedFile(repoFullName: String): File? {
        val downloaded = store.downloadedApk(repoFullName) ?: return null
        return File(downloaded.path).takeIf { it.isFile && it.length() > 0L }
    }

    fun clearDownload(repoFullName: String) {
        Downloads.cancel(getApplication(), repoFullName)
        store.downloadedApk(repoFullName)?.let {
            File(it.path).delete()
            File("${it.path}.part").delete()
        }
        store.clearDownloadedApk(repoFullName)
        updateEntry(repoFullName) { it.copy(downloadReady = false, downloadProgress = 0) }
    }

    fun assignPackage(entry: AppEntry, packageName: String) {
        if (packageName.isBlank()) return
        store.setPackageForRepo(entry.repoFullName, packageName)
        store.setPackageOwner(packageName, entry.repoFullName)
        refreshInstalled()
    }

    fun saveOverride(repoFullName: String, displayName: String?, description: String?) {
        overrides.set(repoFullName, displayName, description)
        updateEntry(repoFullName) { entry ->
            entry.copy(
                displayName = displayName?.trim()?.ifBlank { null } ?: entry.displayName,
                description = description?.trim()?.ifBlank { null } ?: entry.description,
            )
        }
    }

    fun updateSettings(owner: String, token: String?) {
        store.setOwner(owner)
        store.setGithubToken(token)
        market.setOwner(store.owner())
        scanJob?.cancel()
        scanJob = null
        scan(force = true)
    }

    fun currentOwner(): String = store.owner()

    fun currentToken(): String? = store.githubToken()

    private fun observeDownloads() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(Downloads.TAG_ALL).collect { infos ->
                applyDownloads(infos)
            }
        }
    }

    private fun applyDownloads(infos: List<WorkInfo>) {
        if (infos.isEmpty()) return
        val byRepo = HashMap<String, WorkInfo>()
        val attempts = HashMap<String, Long>()
        for (info in infos) {
            val repo = Downloads.repoFromTags(info.tags) ?: continue
            val attempt = Downloads.attemptFromTags(info.tags)
            val current = byRepo[repo]
            val currentAttempt = attempts[repo] ?: -1L
            if (current == null || attempt > currentAttempt ||
                (attempt == currentAttempt && statePriority(info.state) < statePriority(current.state))
            ) {
                byRepo[repo] = info
                attempts[repo] = attempt
            }
        }
        updateEntries { entries ->
            entries.map { entry ->
                val info = byRepo[entry.repoFullName] ?: return@map entry
                val progress = info.progress.getInt(Downloads.KEY_PROGRESS, entry.downloadProgress)
                    .coerceIn(0, 100)
                when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> entry.copy(
                        isDownloading = true,
                        downloadProgress = progress,
                        downloadReady = false,
                        note = null,
                    )

                    WorkInfo.State.SUCCEEDED -> {
                        val downloaded = store.downloadedApk(entry.repoFullName)
                        val ready = downloaded != null &&
                            File(downloaded.path).isFile &&
                            (downloaded.tag == entry.latestTag || entry.latestTag == null)
                        entry.copy(
                            isDownloading = false,
                            downloadProgress = 100,
                            downloadReady = ready,
                            note = null,
                        )
                    }

                    WorkInfo.State.FAILED -> entry.copy(
                        isDownloading = false,
                        downloadProgress = 0,
                        note = info.outputData.getString(Downloads.KEY_ERROR) ?: "Stažení selhalo.",
                    )

                    WorkInfo.State.CANCELLED -> entry.copy(
                        isDownloading = false,
                        downloadProgress = 0,
                    )
                }
            }
        }
    }

    private fun reconcile(list: MutableList<AppEntry>, installed: Map<String, InstalledInfo>) {
        val byPackage = LinkedHashMap<String, MutableList<AppEntry>>()
        for (entry in list) {
            entry.note = null
            val resolved = store.packageForRepo(entry.repoFullName) ?: entry.manifestPackage
            entry.packageName = resolved
            val info = InstalledApps.exact(installed, resolved)
            val known = store.knownRelease(entry.repoFullName)
            val decision = UpdateRules.decide(
                installedVersionCode = info?.versionCode,
                installedTag = store.installedTag(entry.repoFullName),
                knownTag = known?.tag,
                knownVersionCode = known?.versionCode,
                latestTag = entry.latestTag,
            )
            decision.learnedTag?.let { store.setInstalledTag(entry.repoFullName, it) }

            if (info != null) {
                entry.isInstalled = true
                entry.installedVersion = info.versionName ?: info.versionCode.toString()
                entry.installedVersionCode = info.versionCode
                entry.hasUpdate = decision.hasUpdate
                byPackage.getOrPut(info.packageName) { mutableListOf() }.add(entry)
            } else {
                entry.isInstalled = false
                entry.installedVersion = null
                entry.installedVersionCode = null
                entry.hasUpdate = false
                if (store.installedTag(entry.repoFullName) != null) {
                    store.setInstalledTag(entry.repoFullName, null)
                }
            }

            val downloaded = store.downloadedApk(entry.repoFullName)
            entry.downloadReady = downloaded != null &&
                File(downloaded.path).isFile &&
                (downloaded.tag == entry.latestTag || entry.latestTag == null)
            if (entry.downloadReady) entry.downloadProgress = 100
        }

        for ((packageName, group) in byPackage) {
            if (group.size == 1) {
                store.setPackageOwner(packageName, group[0].repoFullName)
                continue
            }
            val preferred = InstalledResolution.preferred(group, store.packageOwner(packageName)) ?: continue
            store.setPackageOwner(packageName, preferred.repoFullName)
            for (entry in group) {
                if (entry === preferred) continue
                entry.isInstalled = false
                entry.hasUpdate = false
                entry.installedVersion = null
                entry.installedVersionCode = null
                entry.note = "Balíček $packageName patří aplikaci ${preferred.displayName}."
            }
        }
    }

    private fun updateEntries(transform: (List<AppEntry>) -> List<AppEntry>) {
        _ui.update { it.copy(entries = transform(it.entries)) }
    }

    private fun updateEntry(repoFullName: String, transform: (AppEntry) -> AppEntry) {
        updateEntries { entries -> entries.map { if (it.repoFullName == repoFullName) transform(it) else it } }
    }

    private fun statePriority(state: WorkInfo.State): Int = when (state) {
        WorkInfo.State.RUNNING -> 0
        WorkInfo.State.ENQUEUED -> 1
        WorkInfo.State.BLOCKED -> 2
        WorkInfo.State.SUCCEEDED -> 3
        WorkInfo.State.FAILED -> 4
        WorkInfo.State.CANCELLED -> 5
    }

    private fun needsSettings(e: Throwable): Boolean =
        e is GitHubMarket.ApiException &&
            (e.code == 401 || e.code == 403 || e.code == 429 || e.rateRemaining == 0)

    private fun friendlyMessage(e: Throwable): String = when (e) {
        is GitHubMarket.ApiException -> e.message ?: "GitHub API chyba."
        is UnknownHostException -> "Bez připojení k internetu."
        is SocketTimeoutException -> "GitHub neodpovídá (timeout). Zkus to znovu."
        is SSLException -> "Chyba zabezpečeného spojení s GitHubem."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Načítání selhalo."
    }
}
