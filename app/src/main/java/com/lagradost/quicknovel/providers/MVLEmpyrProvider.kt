package com.lagradost.quicknovel.providers

import android.util.Log
import com.lagradost.quicknovel.*
import com.lagradost.quicknovel.MainActivity.Companion.app
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File

private val tagCache = mutableMapOf<Int, Long>()

// Cache to store chapters per novelCode in-session
private val chapterListCache = LruCache<Int, List<ChapterData>>(20) // Cache up to 20 novels' chapters

private val chapterContentCache = LruCache<String, String>(20) // Cache last 20 chapter contents

private val novelLastUpdated = LruCache<Int, String>(50) // Cache last updated date per novelCode



fun parseJsonArray(response: String): List<Map<String, Any>> {
    val resultList = mutableListOf<Map<String, Any>>()
    val jsonArray = org.json.JSONArray(response)

    for (i in 0 until jsonArray.length()) {
        val jsonObject = jsonArray.getJSONObject(i)
        val map = mutableMapOf<String, Any>()
        val keys = jsonObject.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.get(key)
            map[key] = value
        }

        resultList.add(map)
    }

    return resultList
}

fun calculateTag(novelCode: Int): Long {
    return tagCache.getOrPut(novelCode) {
        // Existing calculation logic
        val base = 7L
        val modulus = 1999999997L
        var result = 1L
        var power = base
        var exp = novelCode.toLong()

        while (exp > 0) {
            if ((exp and 1L) == 1L) result = (result * power) % modulus
            power = (power * power) % modulus
            exp = exp shr 1
        }
        result
    }
}

class LruCache<K, V>(private val maxSize: Int) : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
        return size > maxSize
    }
}


class MVLEmpyrProvider : MainAPI() {
    override val name = "MVLEmpyr"
    override val mainUrl = "https://www.mvlempyr.com"
    override val hasMainPage = true

    override val iconId = R.drawable.icon_mvlempyr
    override val iconBackgroundId = R.color.colorPrimaryDark

    private val apiUrl = "https://chap.heliosarchive.online/wp-json/wp/v2/mvl-novels?"
    private val chapterNamesApiUrl="https://chap.heliosarchive.online/wp-json/wp/v2/posts?";
    private val chapterApiURL = "https://www.mvlempyr.com/chapter/";
    private var fullNovelList: List<Map<String, Any>> = emptyList()

    override val orderBys = listOf(
        "New" to "new",
        "Chapters Desc" to "chapters_desc",
        "Chapters Asc" to "chapters_asc",
        "Average Rating" to "rating",
        "Most Reviewed" to "reviews"
    )
    fun saveFullNovelListCache(list: List<Map<String, Any>>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val jsonObject = JSONObject(item)
            jsonArray.put(jsonObject)
        }

        val file = File(MainActivity.filesDirSafe, "full_novel_list.json")
        file.writeText(jsonArray.toString())
    }
    fun loadFullNovelListCache(): List<Map<String, Any>> {
        val file = File(MainActivity.filesDirSafe, "full_novel_list.json")
        if (!file.exists()) return emptyList()

        val resultList = mutableListOf<Map<String, Any>>()
        val jsonArray = JSONArray(file.readText())

        for (i in 0 until jsonArray.length()) {
            val jsonObject = jsonArray.getJSONObject(i)
            val map = mutableMapOf<String, Any>()

            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = jsonObject.get(key)
            }
            resultList.add(map)
        }

        return resultList
    }
    fun isCacheValid(): Boolean {
        val file = File(MainActivity.filesDirSafe, "full_novel_list.json")
        return file.exists() && (System.currentTimeMillis() - file.lastModified() < 24 * 60 * 60 * 1000L)
    }

    suspend fun ensureFullNovelListLoaded() {
        if (fullNovelList.isNotEmpty()) return

        if (isCacheValid()) {
            Log.d("MVLEmpyrProvider", "Loading fullNovelList from cache")
            fullNovelList = loadFullNovelListCache()
        } else {
            Log.d("MVLEmpyrProvider", "Fetching fullNovelList from API")
            Log.d("MVLEmpyrProvider", "${apiUrl}per_page=10000")
            val response = app.get("${apiUrl}per_page=10000", timeout = 60).body.string()
            fullNovelList = parseJsonArray(response)
            saveFullNovelListCache(fullNovelList)
        }
        //Log.d("MVLEmpyrProvider","${fullNovelList.size}")
    }



    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val itemsPerPage = 30

        // Fetch only once when fullNovelList is empty
        ensureFullNovelListLoaded();


        val sortedItems = when (orderBy) {
            "new" -> fullNovelList.sortedByDescending { it["createdOn"] as? String }
            "chapters_desc" -> fullNovelList.sortedByDescending { (it["total-chapters"] as? Number)?.toInt() ?: 0 }
            "chapters_asc" -> fullNovelList.sortedBy { (it["total-chapters"] as? Number)?.toInt() ?: 0 }
            "rating" -> fullNovelList.sortedByDescending { (it["average-review"] as? Number)?.toDouble() ?: 0.0 }
            "reviews" -> fullNovelList.sortedByDescending { (it["total-reviews"] as? Number)?.toInt() ?: 0 }
            else -> fullNovelList
        }

        val pagedItems = sortedItems.drop((page - 1) * itemsPerPage).take(itemsPerPage)

        val items = pagedItems.mapNotNull { item ->
            val name = item["name"] as? String ?: return@mapNotNull null
            val slug = item["slug"] as? String ?: return@mapNotNull null
            val novelCode = (item["novel-code"] as? Number)?.toInt() ?: return@mapNotNull null
            val coverUrl = "https://assets.mvlempyr.app/images/300/${novelCode}.webp"
            val chapterCount=item["total-chapters"].toString()
            val link=fixUrlNull("/novel/$slug")?: return@mapNotNull null

            newSearchResponse(name, link) {
                posterUrl = coverUrl;
                totalChapterCount=chapterCount
            }
        }

        return HeadMainPageResponse(mainUrl, items)

    }

    override suspend fun search(query: String): List<SearchResponse> {
        //Log.d("MVLEmpyrProvider", "Searching for: $query")

        ensureFullNovelListLoaded()

        val filteredItems = fullNovelList.filter { item ->
            val queryLower = query.lowercase()

            val name = (item["name"] as? String)?.lowercase() ?: ""
            val author = (item["author-name"] as? String)?.lowercase() ?: ""
            val tags = (item["tags"] as? List<*>)?.joinToString(" ")?.lowercase() ?: ""
            val genres = (item["genre"] as? List<*>)?.joinToString(" ")?.lowercase() ?: ""
            val synopsis = (item["synopsis-text"] as? String)?.lowercase() ?: ""
            val associatedNames = (item["associated-names"] as? String)?.lowercase() ?: ""

            name.contains(queryLower) ||
                    author.contains(queryLower) ||
                    tags.contains(queryLower) ||
                    genres.contains(queryLower) ||
                    synopsis.contains(queryLower) ||
                    associatedNames.contains(queryLower)
        }

        return filteredItems.mapNotNull { item ->
            val name = item["name"] as? String ?: return@mapNotNull null
            val slug = item["slug"] as? String ?: return@mapNotNull null
            val novelCode = (item["novel-code"] as? Number)?.toInt() ?: return@mapNotNull null
            val coverUrl = "https://assets.mvlempyr.app/images/300/${novelCode}.webp"
            val chapterCount=item["total-chapters"].toString()

            //Log.d("MVLEmpyrProvider", "Search Result: name=$name, slug=$slug")

            val url = fixUrlNull("/novel/$slug") ?: return@mapNotNull null

            newSearchResponse(name, url) {
                this.posterUrl = coverUrl
                this.latestChapter = chapterCount
                this.totalChapterCount=chapterCount
            }
        }
    }


    override suspend fun load(url: String): LoadResponse {
        val slugRegex = Regex("/novel/([^/]+)")
        val slug = slugRegex.find(url)?.groupValues?.get(1) ?: throw ErrorLoadingException("Slug not found")

        // Fetch only once when fullNovelList is empty
        ensureFullNovelListLoaded();


        val novelData = fullNovelList.find { it["slug"] == slug } ?: throw ErrorLoadingException("Novel not found")

        val name = novelData["name"] as? String ?: throw ErrorLoadingException("Name not found")
        Log.d("MVLEmpyrProvider", "Load Result: name=$name, slug=$slug")
        val author = novelData["author-name"] as? String
        val novelCode = (novelData["novel-code"] as? Number)?.toInt() ?: 0
        val poster = "https://assets.mvlempyr.app/images/300/${novelCode}.webp"
        val synopsisHtml = novelData["synopsis"] as? String
        val synopsisText = Jsoup.parse(synopsisHtml).text()
        val status = novelData["status"] as? String
        val totalChapters = (novelData["total-chapters"] as? Number)?.toInt() ?: 0
        val tag = calculateTag(novelCode)


        //Log.d("MVLEmpyrProvider", "${totalChapters}")

        // ====================== Check Latest Chapter Date ========================
        val latestChapterApiUrl = "$chapterNamesApiUrl" + "tags=$tag&per_page=1&page=1"
        val latestChapterResponse = app.get(latestChapterApiUrl).parsed<List<Map<String, Any>>>()
        val latestChapterDate = latestChapterResponse.firstOrNull()?.get("date") as? String

        val cachedDate = novelLastUpdated[novelCode]
        val cachedChapters = chapterListCache[novelCode]

        if (cachedDate != null && latestChapterDate != null && cachedDate == latestChapterDate && cachedChapters != null) {
            // Cache is valid, no updates detected
            //Log.d("MVLEmpyrProvider", "No updates. Loaded chapters from cache for novelCode=$novelCode")
            return newStreamResponse(name, fixUrl(url), cachedChapters) {
                this.author = author
                this.posterUrl = poster
                this.synopsis = synopsisText
                setStatus(status)
            }
        }
        // =========================================================================



        val chapters = mutableListOf<ChapterData>()

        var currentPage = 1
        val perPage = 500
        var fetchedAll = false

        while (!fetchedAll) {
            val chaptersApiUrl = chapterNamesApiUrl+"tags=$tag&per_page=$perPage&page=$currentPage"
            //Log.d("MVLEmpyrProvider", "Fetching chapters page $currentPage")
            val apiResponse = app.get(chaptersApiUrl).parsed<List<Map<String, Any>>>()

            if (apiResponse.isEmpty()) {
                fetchedAll = true
            } else {
                val pageChapters = apiResponse.mapNotNull { item ->
                    val acf = item["acf"] as? Map<*, *> ?: return@mapNotNull null
                    val chapterName = acf["ch_name"] as? String ?: return@mapNotNull null
                    val chapterUrl = item["link"] as? String ?: return@mapNotNull null

                    val chapterurlMatch = Regex("/chapter/(\\d+)-(\\d+)").find(chapterUrl)
                    if (chapterurlMatch != null) {
                        //val novelCode = chapterurlMatch.groupValues[1]
                        val chapterNumber = chapterurlMatch.groupValues[2]
                        val newchapterUrl = "${chapterApiURL}${novelCode}-${chapterNumber}"
                        newChapterData(chapterName, fixUrl(newchapterUrl))
                    }
                    else{
                        newChapterData(chapterName, fixUrl(chapterUrl))
                    }

                }

                chapters.addAll(pageChapters)

                // If fewer than perPage results were returned, it means this was the last page
                if (apiResponse.size < perPage) {
                    fetchedAll = true
                } else {
                    currentPage++
                }
            }
        }

        chapters.reverse() // Reverse to have chapter 1 first

        // =================== SAVE TO CACHE ==========================
        chapterListCache[novelCode] = chapters
        //Log.d("MVLEmpyrProvider", "Chapters cached for novelCode=$novelCode")
        // ============================================================

        return newStreamResponse(name,fixUrl(url), chapters) {
            this.author = author
            this.posterUrl = poster
            this.synopsis = synopsisText
            setStatus(status)
        }
    }



    override suspend fun loadHtml(url: String): String? {

        chapterContentCache[url]?.let {
            //Log.d("MVLEmpyrProvider", "Loaded chapter content from cache for $url")
            return it
        }


        val fullUrl = fixUrl(url)
        //Log.d("MVLEmpyrProvider", "Loading Chapter HTML from URL: $fullUrl")

        try {
            val document = app.get(fullUrl).document
            val ContentElement = document.selectFirst("div#chapter.ct-text-block")
            val content = ContentElement?.html()

            if (content != null) {
               // Log.d("MVLEmpyrProvider", "Chapter Content Loaded Successfully from Primary URL")
                chapterContentCache[url] = content
                return content
            } else {
                Log.e("MVLEmpyrProvider", "Chapter Content NOT FOUND in Primary URL, attempting fallback...")
            }
        } catch (e: Exception) {
            Log.e("MVLEmpyrProvider", "Primary URL failed with exception: ${e.message}")
        }


        return  null;
    }

}
