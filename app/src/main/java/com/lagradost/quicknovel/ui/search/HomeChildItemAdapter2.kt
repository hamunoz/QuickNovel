package com.lagradost.quicknovel.ui.search


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_READ_AT
import com.lagradost.quicknovel.HISTORY_FOLDER
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.util.ResultCached
import com.lagradost.quicknovel.databinding.HomeResultGridBinding
import com.lagradost.quicknovel.util.UIHelper.setImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class HomeChildItemAdapter2(
    private val viewModel: SearchViewModel,
) :
    ListAdapter<SearchResponse, HomeChildItemAdapter2.HomeChildItemAdapter2Holder>(DiffCallback()) {

    private val cachedByApiAndUrl: ConcurrentHashMap<String, ResultCached> = ConcurrentHashMap()
    private val cachedByApiAndName: ConcurrentHashMap<String, ResultCached> = ConcurrentHashMap()
    private val unreadByApiAndUrl: ConcurrentHashMap<String, Int?> = ConcurrentHashMap()
    private val indexMutex = Mutex()
    @Volatile private var indexBuilt: Boolean = false


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HomeChildItemAdapter2Holder {
        val binding =
            HomeResultGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HomeChildItemAdapter2Holder(binding, viewModel)
    }

    override fun onBindViewHolder(holder: HomeChildItemAdapter2Holder, position: Int) {
        val currentItem = getItem(position)
        holder.bind(currentItem)
    }

    inner class HomeChildItemAdapter2Holder(
        private val binding: HomeResultGridBinding,
        private val viewModel: SearchViewModel
    ) :
        RecyclerView.ViewHolder(binding.root) {


        private var boundKey: String? = null

        fun bind(card: SearchResponse) {
            val key = "${card.apiName}|${card.url}"
            boundKey = key

            binding.apply {
                imageView.apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setImage(card.image)

                    setOnClickListener {
                        viewModel.load(card)
                    }

                    setOnLongClickListener {
                        viewModel.showMetadata(card)
                        return@setOnLongClickListener true
                    }
                }
                imageText.text = card.name

                unreadBadge.isVisible = false
                val cachedUnread = unreadByApiAndUrl[key]
                if (cachedUnread != null) {
                    if (cachedUnread > 0) {
                        unreadBadge.text = formatBadgeCount(cachedUnread)
                        unreadBadge.isVisible = true
                    }
                } else {
                    viewModel.viewModelScope.launch {
                        val unread = withContext(Dispatchers.IO) {
                            computeUnreadCount(card)
                        }
                        unreadByApiAndUrl[key] = unread
                        if (boundKey == key && unread != null && unread > 0) {
                            unreadBadge.text = formatBadgeCount(unread)
                            unreadBadge.isVisible = true
                        }
                    }
                }
            }
        }
    }

    private fun formatBadgeCount(count: Int): String {
        return if (count > 9999) "9999+" else count.toString()
    }

    private suspend fun ensureIndex() {
        if (indexBuilt) return
        indexMutex.withLock {
            if (indexBuilt) return

            fun indexFolder(folder: String) {
                val keys = getKeys(folder) ?: return
                val prefix = "$folder/"
                for (fullKey in keys) {
                    if (!fullKey.startsWith(prefix)) continue
                    val path = fullKey.removePrefix(prefix)
                    val cached = com.lagradost.quicknovel.BaseApplication.getKey<ResultCached>(folder, path)
                        ?: continue
                    cachedByApiAndUrl["${cached.apiName}|${cached.source}"] = cached
                    cachedByApiAndName["${cached.apiName}|${normalizeNameKey(cached.name)}"] = cached
                }
            }

            indexFolder(RESULT_BOOKMARK)
            indexFolder(HISTORY_FOLDER)
            indexBuilt = true
        }
    }

    private fun normalizeNameKey(name: String): String {
        return name.lowercase().replace("\\s+".toRegex(), " ").trim()
    }

    private suspend fun computeUnreadCount(card: SearchResponse): Int? {
        ensureIndex()
        val cached = cachedByApiAndUrl["${card.apiName}|${card.url}"]
            ?: cachedByApiAndName["${card.apiName}|${normalizeNameKey(card.name)}"]
            ?: return null
        val total = cached.totalChapters
        if (total <= 0) return null

        val readKeys = getKeys(EPUB_CURRENT_POSITION_READ_AT) ?: return total
        val prefix = "$EPUB_CURRENT_POSITION_READ_AT/${cached.name}/"
        val readCount = readKeys.count { it.startsWith(prefix) }

        return (total - readCount).coerceAtLeast(0)
    }

    class DiffCallback : DiffUtil.ItemCallback<SearchResponse>() {
        override fun areItemsTheSame(oldItem: SearchResponse, newItem: SearchResponse): Boolean =
            oldItem.url == newItem.url

        override fun areContentsTheSame(oldItem: SearchResponse, newItem: SearchResponse): Boolean =
            oldItem == newItem
    }
}
