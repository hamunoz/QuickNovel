package com.lagradost.quicknovel.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.*
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.util.AppUtils.parseJson
import org.jsoup.Jsoup

class SkyNovelsProvider : MainAPI() {
    override val name = "SkyNovels"
    override val mainUrl = "https://www.skynovels.net"
    override val lang = "es"
    override val hasMainPage = false

    private val apiBase = "https://api.skynovels.net/api"

    private fun buildPoster(image: String?, isChapter: Boolean = false): String? {
        if (image.isNullOrBlank()) return null
        // /api/get-image/<file>/novels/<true|false>
        return "https://api.skynovels.net/api/get-image/$image/novels/${if (isChapter) "true" else "false"}"
    }

    // region JSON models
    private data class NovelsRoot(
        val novels: List<Novel> = emptyList()
    )

    private data class Novel(
        val id: Long,
        @JsonProperty("nvl_title") val title: String? = null,
        @JsonProperty("nvl_name") val slug: String? = null,
        @JsonProperty("nvl_writer") val writer: String? = null,
        @JsonProperty("nvl_content") val content: String? = null,
        @JsonProperty("nvl_status") val status: String? = null,
        @JsonProperty("nvl_rating") val rating5: Double? = null,
        @JsonProperty("image") val image: String? = null,
        val genres: List<Genre> = emptyList(),
        @JsonProperty("nvl_chapters") val chaptersCount: Int? = null,
    )

    private data class Genre(
        @JsonProperty("genre_name") val name: String? = null,
    )

    private data class ChaptersRoot(
        val chapters: List<Chapter> = emptyList()
    )

    private data class Chapter(
        val id: Long? = null,
        val order: Int? = null,
        val title: String? = null,
        @JsonProperty("chp_title") val cTitle: String? = null,
        val name: String? = null,
        val slug: String? = null,
        @JsonProperty("updated_at") val updatedAt: String? = null,
    )
    // endregion

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiBase/novels?search=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val res = app.get(url, headers = mapOf("referer" to mainUrl))
        val root = parseJson<NovelsRoot>(res.text)
        return root.novels.mapNotNull { n ->
            val title = n.title ?: return@mapNotNull null
            val slug = n.slug ?: return@mapNotNull null
            newSearchResponse(
                name = title,
                url = "$mainUrl/novelas/${n.id}/$slug",
            ) {
                posterUrl = buildPoster(n.image)
                rating = ((n.rating5 ?: 0.0) * 200).toInt()
                latestChapter = n.chaptersCount?.let { "Capítulos: $it" }
            }
        }
    }

    private fun parseIdFromUrl(url: String): Long? {
        val regex = "/novelas/(\\d+)/".toRegex()
        val match = regex.find(url) ?: return null
        return match.groupValues.getOrNull(1)?.toLongOrNull()
    }

    private suspend fun fetchNovel(id: Long): Novel? {
        val url = "$apiBase/novels?id=$id"
        val res = app.get(url, headers = mapOf("referer" to mainUrl))
        val root = parseJson<NovelsRoot>(res.text)
        return root.novels.firstOrNull()
    }

    private suspend fun fetchChapters(id: Long, slug: String?): List<ChapterData> {
        // The API sometimes responds with an error string. We'll try a couple of known patterns and stop on first success.
        val possibleUrls = listOf(
            "$apiBase/novel/$id/chapters?page=1&limit=5000",
            // keep alternative variants in case backend changes
            "$apiBase/novel/$id/chapters?page=1",
        )
        for (endpoint in possibleUrls) {
            try {
                val res = app.get(endpoint, headers = mapOf("referer" to mainUrl))
                // Some responses can be {"message":"El servidor no responde"}
                if (res.text.contains("El servidor no responde", ignoreCase = true)) continue
                if (res.text.contains("Cannot GET")) continue
                val root = parseJson<ChaptersRoot>(res.text)
                if (root.chapters.isEmpty()) continue
                return root.chapters.mapIndexed { index, c ->
                    val order = c.order ?: (index + 1)
                    val cName = c.title ?: c.cTitle ?: c.name ?: c.slug ?: "Capítulo $order"
                    val cSlug = c.slug ?: c.name ?: ("capitulo-" + order)
                    val chapterUrl = "$mainUrl/novelas/$id/${slug ?: ""}/${c.id ?: 0}/$cSlug"
                    newChapterData(name = cName, url = chapterUrl) {
                        dateOfRelease = c.updatedAt
                    }
                }
            } catch (_: Throwable) {
                // try next variant
            }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = parseIdFromUrl(url) ?: return null
        val novel = fetchNovel(id) ?: return null
        val chapters = try { fetchChapters(id, novel.slug) } catch (_: Throwable) { emptyList() }

        return newStreamResponse(
            name = novel.title ?: (novel.slug ?: "Novel $id"),
            url = "$mainUrl/novelas/${novel.id}/${novel.slug ?: ""}",
            data = chapters
        ) {
            author = novel.writer
            posterUrl = buildPoster(novel.image)
            synopsis = novel.content?.synopsis()
            tags = novel.genres.mapNotNull { it.name }
            setStatus(novel.status)
            rating = ((novel.rating5 ?: 0.0) * 200).toInt()
        }
    }

    override suspend fun loadHtml(url: String): String? {
        // Try to grab server-rendered chapter content first
        val doc = app.get(url, headers = mapOf("referer" to mainUrl)).document
        // Possible containers used on site; keep several selectors for resilience
        val candidates = listOf(
            ".skn-chp-chapter .skn-chp-chapter-content",
            ".skn-chp-chapter-content",
            "markdown",
        )
        val element = candidates.asSequence().mapNotNull { sel -> doc.selectFirst(sel) }.firstOrNull()
        val html = element?.html()
        if (!html.isNullOrBlank()) return Jsoup.parse(html).html()
        return null
    }
}