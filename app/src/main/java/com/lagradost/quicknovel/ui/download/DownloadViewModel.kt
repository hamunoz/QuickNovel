package com.lagradost.quicknovel.ui.download

import android.content.DialogInterface
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.quicknovel.BaseApplication.Companion.context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.BookDownloader2.currentDownloads
import com.lagradost.quicknovel.BookDownloader2.currentDownloadsMutex
import com.lagradost.quicknovel.BookDownloader2.downloadInfoMutex
import com.lagradost.quicknovel.BookDownloader2.downloadProgress
import com.lagradost.quicknovel.BookDownloader2.downloadProgressChanged
import com.lagradost.quicknovel.CURRENT_TAB
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.DOWNLOAD_EPUB_LAST_ACCESS
import com.lagradost.quicknovel.DOWNLOAD_NORMAL_SORTING_METHOD
import com.lagradost.quicknovel.DOWNLOAD_SETTINGS
import com.lagradost.quicknovel.DOWNLOAD_SORTING_METHOD
import com.lagradost.quicknovel.DOWNLOAD_UNREAD_ONLY_FILTER
import com.lagradost.quicknovel.DOWNLOAD_COMPLETED_ONLY_FILTER
import com.lagradost.quicknovel.DownloadActionType
import com.lagradost.quicknovel.DownloadFileWorkManager
import com.lagradost.quicknovel.DownloadProgressState
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.LibraryRefreshNotifications
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.MainActivity.Companion.loadResult
import com.lagradost.quicknovel.PreferenceDelegate
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.RESULT_BOOKMARK_STATE
import com.lagradost.quicknovel.RESULT_CHAPTER_FILTER_BOOKMARKED
import com.lagradost.quicknovel.RESULT_CHAPTER_FILTER_DOWNLOADED
import com.lagradost.quicknovel.RESULT_CHAPTER_FILTER_READ
import com.lagradost.quicknovel.RESULT_CHAPTER_FILTER_UNREAD
import com.lagradost.quicknovel.RESULT_CHAPTER_SORT
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_READ_AT
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.mvvm.launchSafe
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.ui.ReadType
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.ResultCached
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.xdrop.fuzzywuzzy.FuzzySearch
import java.util.concurrent.CopyOnWriteArrayList

const val DEFAULT_SORT = 0
const val ALPHA_SORT = 1
const val REVERSE_ALPHA_SORT = 2
const val DOWNLOADSIZE_SORT = 3
const val REVERSE_DOWNLOADSIZE_SORT = 4
const val DOWNLOADPRECENTAGE_SORT = 5
const val REVERSE_DOWNLOADPRECENTAGE_SORT = 6
const val LAST_ACCES_SORT = 7
const val REVERSE_LAST_ACCES_SORT = 8
const val LAST_UPDATED_SORT = 9
const val REVERSE_LAST_UPDATED_SORT = 10

const val CHAPTER_SORT = 11
const val REVERSE_CHAPTER_SORT = 12

const val UNREAD_CHAPTER_SORT = 13
const val REVERSE_UNREAD_CHAPTER_SORT = 14

data class SortingMethod(@StringRes val name: Int, val id: Int, val inverse: Int = id)
class DownloadViewModel : ViewModel() {

    companion object {
        val sortingMethods = arrayOf(
            SortingMethod(R.string.default_sort, DEFAULT_SORT),
            SortingMethod(R.string.recently_sort, LAST_ACCES_SORT, REVERSE_LAST_ACCES_SORT),
            SortingMethod(
                R.string.recently_updated_sort,
                LAST_UPDATED_SORT,
                REVERSE_LAST_UPDATED_SORT
            ),
            SortingMethod(R.string.alpha_sort, ALPHA_SORT, REVERSE_ALPHA_SORT),
            SortingMethod(R.string.download_sort, DOWNLOADSIZE_SORT, REVERSE_DOWNLOADSIZE_SORT),
            SortingMethod(
                R.string.download_perc, DOWNLOADPRECENTAGE_SORT,
                REVERSE_DOWNLOADPRECENTAGE_SORT
            ),
        )

        val normalSortingMethods = arrayOf(
            SortingMethod(R.string.default_sort, DEFAULT_SORT),
            SortingMethod(R.string.recently_sort, LAST_ACCES_SORT, REVERSE_LAST_ACCES_SORT),
            SortingMethod(R.string.alpha_sort, ALPHA_SORT, REVERSE_ALPHA_SORT),
            SortingMethod(R.string.unread_chapters_sort, UNREAD_CHAPTER_SORT, REVERSE_UNREAD_CHAPTER_SORT),
        )
    }

    val readList = arrayListOf(
        ReadType.READING,
        ReadType.ON_HOLD,
        ReadType.PLAN_TO_READ,
        ReadType.COMPLETED,
        ReadType.DROPPED,
        ReadType.TRASH,
    )

    var activeQuery: String = ""
    val _pages: MutableLiveData<List<Page>> = MutableLiveData(null)
    val pages: LiveData<List<Page>> = _pages

    var currentTab: MutableLiveData<Int> =
        MutableLiveData<Int>(getKey(DOWNLOAD_SETTINGS, CURRENT_TAB, 0))

    private val _isRefreshing: MutableLiveData<Boolean> = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    data class RefreshProgress(
        val current: Int,
        val total: Int,
        val name: String,
        val changedChapters: Int?
    )

    private val _refreshProgress: MutableLiveData<RefreshProgress> = MutableLiveData(null)
    val refreshProgress: LiveData<RefreshProgress> = _refreshProgress

    private val refreshMutex = Mutex()

    @Volatile
    private var readChaptersByNovelName: Map<String, Int> = emptyMap()

    fun switchPage(position: Int) {
        setKey(DOWNLOAD_SETTINGS, CURRENT_TAB, position)
        currentTab.postValue(position)
    }

    fun refreshCurrentTab() = viewModelScope.launchSafe {
        refreshMutex.withLock {
            val tabIndex = currentTab.value ?: 0
            _isRefreshing.postValue(true)
            try {
                if (tabIndex == 0) {
                    // Downloads refresh is already handled via WorkManager.
                    refresh()
                } else {
                    refreshBookmarkCategory(tabIndex)
                }
            } finally {
                _isRefreshing.postValue(false)
            }
        }
    }

    private suspend fun refreshBookmarkCategory(tabIndex: Int) {
        val page = _pages.value?.getOrNull(tabIndex) ?: return
        val items = page.unsortedItems.filterIsInstance<ResultCached>()
        if (items.isEmpty()) return

        val ctx = context ?: return
        LibraryRefreshNotifications.showProgress(
            ctx,
            current = 0,
            total = items.size,
            title = ctx.getString(R.string.title_download),
            details = ctx.getString(R.string.loading)
        )

        var refreshed = 0
        var changed = 0
        withContext(Dispatchers.IO) {
            for ((index, cached) in items.withIndex()) {
                _refreshProgress.postValue(
                    RefreshProgress(
                        current = index + 1,
                        total = items.size,
                        name = cached.name,
                        changedChapters = null
                    )
                )

                LibraryRefreshNotifications.showProgress(
                    ctx,
                    current = index + 1,
                    total = items.size,
                    title = ctx.getString(R.string.title_download),
                    details = "${index + 1}/${items.size}: ${cached.name}"
                )

                val repo = Apis.getApiFromNameOrNull(cached.apiName) ?: continue
                when (val res = repo.load(cached.source, allowCache = false)) {
                    is Resource.Success -> {
                        val load = res.value
                        val totalChapters = (load as? StreamResponse)?.data?.size ?: cached.totalChapters
                        val delta = totalChapters - cached.totalChapters
                        if (delta != 0) changed += 1

                        // Keep name stable to avoid breaking read-key matching.
                        val updated = cached.copy(
                            author = load.author ?: cached.author,
                            poster = load.posterUrl ?: cached.poster,
                            tags = load.tags ?: cached.tags,
                            rating = load.rating ?: cached.rating,
                            totalChapters = totalChapters,
                            cachedTime = System.currentTimeMillis(),
                            synopsis = load.synopsis ?: cached.synopsis
                        )

                        setKey(RESULT_BOOKMARK, cached.id.toString(), updated)

                        _refreshProgress.postValue(
                            RefreshProgress(
                                current = index + 1,
                                total = items.size,
                                name = cached.name,
                                changedChapters = delta
                            )
                        )

                        refreshed += 1

                        // Push occasional UI updates so changes are visible while refreshing.
                        val shouldUpdateUi = items.size <= 20 || (index % 5 == 4) || index == items.lastIndex
                        if (shouldUpdateUi) {
                            // Rebuild pages from storage to reflect updated totals.
                            // (Runs some IO; throttled above.)
                            loadAllDataInternal(refreshAll = false)
                        }
                    }

                    else -> {
                        // Ignore failures; keep existing cached snapshot.
                    }
                }
            }
        }

        LibraryRefreshNotifications.finish(
            ctx,
            title = ctx.getString(R.string.title_download),
            details = "Updated ${refreshed}/${items.size} (changed: $changed)"
        )
    }

    fun refreshCard(card: DownloadFragment.DownloadDataLoaded) {
        DownloadFileWorkManager.download(card, context ?: return)
    }

    fun pause(card: DownloadFragment.DownloadDataLoaded) {
        BookDownloader2.addPendingAction(card.id, DownloadActionType.Pause)
    }

    fun resume(card: DownloadFragment.DownloadDataLoaded) {
        BookDownloader2.addPendingAction(card.id, DownloadActionType.Resume)
    }

    fun load(card: ResultCached) {
        loadResult(card.source, card.apiName)
    }

    fun stream(card: ResultCached) {
        BookDownloader2.stream(card)
    }

    fun search(query: String) {
        activeQuery = query.lowercase()
        resortAllData()
    }

    fun readEpub(card: DownloadFragment.DownloadDataLoaded) = ioSafe {
        try {
            cardsDataMutex.withLock {
                cardsData[card.id] = cardsData[card.id]?.copy(generating = true) ?: return@withLock
            }
            postCards()
            BookDownloader2.readEpub(
                card.id,
                card.downloadedCount.toInt(),
                card.author,
                card.name,
                card.apiName,
                card.synopsis
            )
        } finally {
            setKey(DOWNLOAD_EPUB_LAST_ACCESS, card.id.toString(), System.currentTimeMillis())
            cardsDataMutex.withLock {
                cardsData[card.id] = cardsData[card.id]?.copy(generating = false) ?: return@withLock
            }
            postCards()
        }
    }

    @WorkerThread
    suspend fun refreshInternal() {
        val allValues = cardsDataMutex.withLock {
            cardsData.values
        }

        val values = currentDownloadsMutex.withLock {
            allValues.filter { card ->
                val notImported = !card.isImported
                val canDownload =
                    card.downloadedTotal <= 0 || (card.downloadedCount * 100 / card.downloadedTotal) > 90
                val notDownloading = !currentDownloads.contains(
                    card.id
                )
                notImported && canDownload && notDownloading
            }
        }

        downloadInfoMutex.withLock {
            for (card in values) {
                downloadProgress[card.id]?.apply {
                    state = DownloadState.IsPending
                    lastUpdatedMs = System.currentTimeMillis()
                    downloadProgressChanged.invoke(card.id to this)
                }
            }
        }

        for (card in values) {
            if (card.downloadedTotal <= 0 || (card.downloadedCount * 100 / card.downloadedTotal) > 90) {
                BookDownloader2.downloadWorkThread(card)
            }
        }
    }

    fun refresh() {
        DownloadFileWorkManager.refreshAll(this@DownloadViewModel, context ?: return)
    }

    fun showMetadata(card: DownloadFragment.DownloadDataLoaded) {
        MainActivity.loadPreviewPage(card)
    }

    fun importEpub() {
        MainActivity.importEpub()
    }

    fun showMetadata(card: ResultCached) {
        MainActivity.loadPreviewPage(card)
    }

    fun load(card: DownloadFragment.DownloadDataLoaded) {
        loadResult(card.source, card.apiName)
    }

    fun deleteAlert(card: ResultCached) {
        val dialogClickListener =
            DialogInterface.OnClickListener { _, which ->
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> {
                        delete(card)
                    }

                    DialogInterface.BUTTON_NEGATIVE -> {
                    }
                }
            }
        val act = activity ?: return
        val builder: AlertDialog.Builder = AlertDialog.Builder(act)
        builder.setMessage(act.getString(R.string.permanently_delete_format).format(card.name))
            .setTitle(R.string.delete)
            .setPositiveButton(R.string.delete, dialogClickListener)
            .setNegativeButton(R.string.cancel, dialogClickListener)
            .show()
    }

    fun delete(card: ResultCached) {
        removeKey(RESULT_BOOKMARK, card.id.toString())
        removeKey(RESULT_BOOKMARK_STATE, card.id.toString())
        loadAllData(false)
    }

    fun deleteAlert(card: DownloadFragment.DownloadDataLoaded) {
        val dialogClickListener =
            DialogInterface.OnClickListener { _, which ->
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> {
                        delete(card)
                    }

                    DialogInterface.BUTTON_NEGATIVE -> {
                    }
                }
            }
        val act = activity ?: return
        val builder: AlertDialog.Builder = AlertDialog.Builder(act)
        builder.setMessage(act.getString(R.string.permanently_delete_format).format(card.name))
            .setTitle(R.string.delete)
            .setPositiveButton(R.string.delete, dialogClickListener)
            .setNegativeButton(R.string.cancel, dialogClickListener)
            .show()
    }

    fun delete(card: DownloadFragment.DownloadDataLoaded) {
        BookDownloader2.deleteNovel(card.author, card.name, card.apiName)
    }

    private fun matchesQuery(x: String): Boolean {
        return activeQuery.isBlank() || FuzzySearch.partialRatio(x.lowercase(), activeQuery) > 50
    }

    private fun sortArray(
        currentArray: ArrayList<DownloadFragment.DownloadDataLoaded>,
    ): List<DownloadFragment.DownloadDataLoaded> {
        val newSortingMethod = getKey(DOWNLOAD_SETTINGS, DOWNLOAD_SORTING_METHOD) ?: DEFAULT_SORT
        setKey(DOWNLOAD_SETTINGS, DOWNLOAD_SORTING_METHOD, newSortingMethod)

        return when (newSortingMethod) {
            ALPHA_SORT -> {
                currentArray.sortBy { t -> t.name }
                currentArray
            }

            REVERSE_ALPHA_SORT -> {
                currentArray.sortByDescending { t -> t.name }
                currentArray
            }

            DOWNLOADSIZE_SORT -> {
                currentArray.sortByDescending { t -> t.downloadedCount }
                currentArray
            }

            REVERSE_DOWNLOADSIZE_SORT -> {
                currentArray.sortBy { t -> t.downloadedCount }
                currentArray
            }

            DOWNLOADPRECENTAGE_SORT -> {
                currentArray.sortByDescending { t -> t.downloadedCount.toFloat() / t.downloadedTotal }
                currentArray
            }

            REVERSE_DOWNLOADPRECENTAGE_SORT -> {
                currentArray.sortBy { t -> t.downloadedCount.toFloat() / t.downloadedTotal }
                currentArray
            }

            REVERSE_LAST_ACCES_SORT -> {
                currentArray.sortBy { t ->
                    (getKey<Long>(
                        DOWNLOAD_EPUB_LAST_ACCESS,
                        t.id.toString(),
                        0
                    )!!)
                }
                currentArray
            }

            LAST_UPDATED_SORT -> {
                if (currentArray.any { it.lastDownloaded == null }) {
                    currentArray.sortByDescending { t ->
                        (getKey<Long>(
                            DOWNLOAD_EPUB_LAST_ACCESS,
                            t.id.toString(),
                            0
                        )!!)
                    }
                }
                currentArray.sortByDescending { it.lastDownloaded ?: 0L }
                currentArray
            }

            REVERSE_LAST_UPDATED_SORT -> {
                if (currentArray.any { it.lastDownloaded == null }) {
                    currentArray.sortByDescending { t ->
                        (getKey<Long>(
                            DOWNLOAD_EPUB_LAST_ACCESS,
                            t.id.toString(),
                            0
                        )!!)
                    }
                }
                currentArray.sortBy { it.lastDownloaded ?: 0L }
                currentArray
            }
            //DEFAULT_SORT, LAST_ACCES_SORT
            else -> {
                currentArray.sortByDescending { t ->
                    (getKey<Long>(
                        DOWNLOAD_EPUB_LAST_ACCESS,
                        t.id.toString(),
                        0
                    )!!)
                }
                currentArray
            }
        }.filter { matchesQuery(it.name) }
    }

    private fun sortNormalArray(
        currentArray: ArrayList<ResultCached>,
    ): List<ResultCached> {
        val newSortingMethod =
            getKey(DOWNLOAD_SETTINGS, DOWNLOAD_NORMAL_SORTING_METHOD) ?: DEFAULT_SORT
        setKey(DOWNLOAD_SETTINGS, DOWNLOAD_NORMAL_SORTING_METHOD, newSortingMethod)

        val unreadOnly = getKey(DOWNLOAD_SETTINGS, DOWNLOAD_UNREAD_ONLY_FILTER, false) == true
        val completedOnly = getKey(DOWNLOAD_SETTINGS, DOWNLOAD_COMPLETED_ONLY_FILTER, false) == true
        val readMapSnapshot = readChaptersByNovelName

        fun unreadCount(item: ResultCached): Int {
            val read = readMapSnapshot[item.name] ?: 0
            val total = item.totalChapters
            return (total - read).coerceAtLeast(0)
        }

        fun isCompletedNovel(item: ResultCached): Boolean {
            // Check if status is "Completed"
            if (item.status?.equals("Completed", ignoreCase = true) == true) return true
            // Check if last chapter name contains (end), [end], etc.
            val lastChapter = item.lastChapterName?.lowercase() ?: return false
            val endPatterns = listOf("(end)", "[end]", "(finale)", "[finale]", "(final)", "[final]", "(完)")
            return endPatterns.any { lastChapter.contains(it) }
        }

        return when (newSortingMethod) {
            ALPHA_SORT -> {
                currentArray.sortBy { t -> t.name }
                currentArray
            }

            REVERSE_ALPHA_SORT -> {
                currentArray.sortByDescending { t -> t.name }
                currentArray
            }

            REVERSE_LAST_ACCES_SORT -> {
                currentArray.sortBy { t ->
                    (getKey<Long>(
                        DOWNLOAD_EPUB_LAST_ACCESS,
                        t.id.toString(),
                        0
                    )!!)
                }
                currentArray
            }

            UNREAD_CHAPTER_SORT -> {
                currentArray.sortByDescending { t -> unreadCount(t) }
                currentArray
            }

            REVERSE_UNREAD_CHAPTER_SORT -> {
                currentArray.sortBy { t -> unreadCount(t) }
                currentArray
            }
            // DEFAULT_SORT, LAST_ACCES_SORT
            else -> {
                currentArray.sortByDescending { t ->
                    (getKey<Long>(
                        DOWNLOAD_EPUB_LAST_ACCESS,
                        t.id.toString(),
                        0
                    )!!)
                }
                currentArray
            }
        }.filter { cached ->
            matchesQuery(cached.name) && 
            (!unreadOnly || (readMapSnapshot[cached.name] ?: 0) == 0) &&
            (!completedOnly || isCompletedNovel(cached))
        }
    }

    // very shitty copy as we need to deep copy to actually update it
    fun resortAllData() {
        val data = _pages.value ?: return
        if (data.isEmpty()) {
            return
        }
        val list = arrayListOf<Page>()
        list.add(
            data[0].copy(
                unsortedItems = data[0].unsortedItems,
                items = sortArray(ArrayList(data[0].unsortedItems.map { (it as DownloadFragment.DownloadDataLoaded).copy() }))
            )
        )
        for (i in 1..data.lastIndex) {
            list.add(
                data[i].copy(
                    unsortedItems = data[i].unsortedItems,
                    items = sortNormalArray(ArrayList(data[i].unsortedItems.map { (it as ResultCached).copy() }))
                )
            )
        }
        _pages.postValue(list)
    }

    fun loadAllData(refreshAll: Boolean) = viewModelScope.launch {
        loadAllDataInternal(refreshAll)
    }

    private suspend fun loadAllDataInternal(refreshAll: Boolean) {
        if (refreshAll) fetchAllData(false)
        val mapping: HashMap<Int, ArrayList<ResultCached>> = hashMapOf(
            ReadType.PLAN_TO_READ.prefValue to arrayListOf(),
            ReadType.DROPPED.prefValue to arrayListOf(),
            ReadType.COMPLETED.prefValue to arrayListOf(),
            ReadType.ON_HOLD.prefValue to arrayListOf(),
            ReadType.READING.prefValue to arrayListOf(),
            ReadType.TRASH.prefValue to arrayListOf(),
        )

        withContext(Dispatchers.IO) {
            val readCounts = HashMap<String, Int>()
            val prefix = "$EPUB_CURRENT_POSITION_READ_AT/"
            for (key in (getKeys(EPUB_CURRENT_POSITION_READ_AT) ?: emptyList())) {
                if (!key.startsWith(prefix)) continue
                val rest = key.removePrefix(prefix)
                val novelName = rest.substringBefore('/')
                if (novelName.isBlank()) continue
                readCounts[novelName] = (readCounts[novelName] ?: 0) + 1
            }
            readChaptersByNovelName = readCounts

            val keys = getKeys(RESULT_BOOKMARK_STATE)
            for (key in keys ?: emptyList()) {
                val type = getKey<Int>(key) ?: continue
                val id = key.replaceFirst(
                    RESULT_BOOKMARK_STATE,
                    RESULT_BOOKMARK
                )
                val cached = getKey<ResultCached>(id) ?: continue
                mapping[type]?.add(cached)
            }
        }

        val pages = mutableListOf(
            getDownloadedCards(),
        )
        for (read in readList) {
            pages.add(
                Page(
                    read.name,
                    unsortedItems = mapping[read.prefValue]!!,
                    items = sortNormalArray(mapping[read.prefValue]!!)
                ),
            )
        }
        _pages.postValue(pages)
    }

    private suspend fun getDownloadedCards(): Page = cardsDataMutex.withLock {
        Page(
            ReadType.NONE.name, unsortedItems = ArrayList(cardsData.values),
            items =
                sortArray(ArrayList(cardsData.values))
        )
    }


    private suspend fun postCards() {
        _pages.value?.let { data ->
            val list = CopyOnWriteArrayList(data)
            if (list.isEmpty()) {
                list.add(getDownloadedCards())
            } else {
                list[0] = getDownloadedCards()
            }
            _pages.postValue(list)
        }
    }

    init {
        BookDownloader2.downloadDataChanged += ::progressDataChanged
        BookDownloader2.downloadProgressChanged += ::progressChanged
        BookDownloader2.downloadDataRefreshed += ::downloadDataRefreshed
        BookDownloader2.downloadRemoved += ::downloadRemoved
    }

    override fun onCleared() {
        super.onCleared()
        BookDownloader2.downloadProgressChanged -= ::progressChanged
        BookDownloader2.downloadDataChanged -= ::progressDataChanged
        BookDownloader2.downloadDataRefreshed -= ::downloadDataRefreshed
        BookDownloader2.downloadRemoved -= ::downloadRemoved
    }

    private val cardsDataMutex = Mutex()
    private val cardsData: HashMap<Int, DownloadFragment.DownloadDataLoaded> = hashMapOf()

    private fun progressChanged(data: Pair<Int, DownloadProgressState>) =
        viewModelScope.launchSafe {
            cardsDataMutex.withLock {
                val (id, state) = data
                val newState = state.eta(context ?: return@launchSafe)
                cardsData[id] = cardsData[id]?.copy(
                    downloadedCount = state.progress,
                    downloadedTotal = state.total,
                    state = state.state,
                    ETA = newState,
                ) ?: return@launchSafe
            }
            postCards()
        }

    private fun downloadRemoved(id: Int) = viewModelScope.launchSafe {
        cardsDataMutex.withLock {
            cardsData -= id
        }
        postCards()
    }

    private fun progressDataChanged(data: Pair<Int, DownloadFragment.DownloadData>) =
        viewModelScope.launchSafe {
            cardsDataMutex.withLock {
                val (id, value) = data
                cardsData[id] = cardsData[id]?.copy(
                    source = value.source,
                    name = value.name,
                    author = value.author,
                    posterUrl = value.posterUrl,
                    rating = value.rating,
                    peopleVoted = value.peopleVoted,
                    views = value.views,
                    synopsis = value.synopsis,
                    tags = value.tags,
                    apiName = value.apiName,
                    lastUpdated = value.lastUpdated,
                    lastDownloaded = value.lastDownloaded
                ) ?: run {
                    DownloadFragment.DownloadDataLoaded(
                        source = value.source,
                        name = value.name,
                        author = value.author,
                        posterUrl = value.posterUrl,
                        rating = value.rating,
                        peopleVoted = value.peopleVoted,
                        views = value.views,
                        synopsis = value.synopsis,
                        tags = value.tags,
                        apiName = value.apiName,
                        downloadedCount = 0,
                        downloadedTotal = 0,
                        ETA = "",
                        state = DownloadState.Nothing,
                        id = id,
                        generating = false,
                        lastUpdated = value.lastUpdated,
                        lastDownloaded = value.lastDownloaded,
                    )
                }
            }
            postCards()
        }

    suspend fun fetchAllData(postCard: Boolean) {
        downloadInfoMutex.withLock {
            cardsDataMutex.withLock {
                BookDownloader2.downloadData.map { (key, value) ->
                    val info = downloadProgress[key] ?: return@map
                    cardsData[key] = DownloadFragment.DownloadDataLoaded(
                        source = value.source,
                        name = value.name,
                        author = value.author,
                        posterUrl = value.posterUrl,
                        rating = value.rating,
                        peopleVoted = value.peopleVoted,
                        views = value.views,
                        synopsis = value.synopsis,
                        tags = value.tags,
                        apiName = value.apiName,
                        downloadedCount = info.progress,
                        downloadedTotal = info.total,
                        ETA = context?.let { ctx -> info.eta(ctx) } ?: "",
                        state = info.state,
                        id = key,
                        generating = false,
                        lastUpdated = value.lastUpdated,
                        lastDownloaded = value.lastDownloaded,
                    )
                }
            }
            if (postCard) postCards()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun downloadDataRefreshed(_id: Int) = viewModelScope.launchSafe {
        fetchAllData(true)
    }
}
