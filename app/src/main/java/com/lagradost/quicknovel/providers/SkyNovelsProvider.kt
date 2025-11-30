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

    private val apiBase = "https://api.skynovels.net/api/"

    private fun buildPoster(image: String?): String? {
        return image?.let { "$apiBase" + "get-image/$it/novels/false" }
    }

    private data class NovelListResponse(
        val novels: List<Novel> = emptyList()
    )

    private data class Novel(
        val id: Int,
        @JsonProperty("nvl_title") val title: String? = null,
        @JsonProperty("nvl_name") val slug: String? = null,
        @JsonProperty("nvl_writer") val writer: String? = null,
        @JsonProperty("nvl_content") val summary: String? = null,
        @JsonProperty("nvl_status") val status: String? = null,
        @JsonProperty("nvl_rating") val rating: Double? = null,
        @JsonProperty("image") val image: String? = null,
        val genres: List<Genre> = emptyList(),
        @JsonProperty("nvl_chapters") val chapters: Int? = null
    )

    private data class Genre(@JsonProperty("genre_name") val name: String? = null)

    private data class NovelDetailResponse(
        val novel: List<NovelDetail> = emptyList()
    )

    private data class NovelDetail(
        val id: Int,
        @JsonProperty("nvl_title") val title: String? = null,
        @JsonProperty("nvl_name") val slug: String? = null,
        @JsonProperty("nvl_writer") val writer: String? = null,
        @JsonProperty("nvl_content") val content: String? = null,
        @JsonProperty("nvl_status") val status: String? = null,
        @JsonProperty("image") val image: String? = null,
        val genres: List<Genre> = emptyList(),
        val volumes: List<Volume> = emptyList()
    )

    private data class Volume(
        val id: Int,
        @JsonProperty("vlm_title") val title: String? = null,
        val chapters: List<Chapter> = emptyList()
    )

    private data class Chapter(
        val id: Int,
        @JsonProperty("chp_index_title") val title: String? = null,
        @JsonProperty("chp_name") val slug: String? = null,
        @JsonProperty("createdAt") val date: String? = null
    )

    private data class ChapterResponse(
        val chapter: List<ChapterContent> = emptyList()
    )

    private data class ChapterContent(
        @JsonProperty("chp_content") val content: String? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get(apiBase + "novels?&q", headers = mapOf("referer" to mainUrl))
        val root = parseJson<NovelListResponse>(res.text)
        return root.novels.filter {
            it.title?.contains(query, ignoreCase = true) == true
        }.mapNotNull { n ->
            val title = n.title ?: return@mapNotNull null
            val slug = n.slug ?: return@mapNotNull null
            newSearchResponse(
                name = title,
                url = "$mainUrl/novelas/${n.id}/$slug"
            ) {
                posterUrl = buildPoster(n.image)
                rating = ((n.rating ?: 0.0) * 200).toInt()
                latestChapter = n.chapters?.let { "Capítulos: $it" }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.split("/").getOrNull(4)?.toIntOrNull() ?: return null
        val res = app.get(apiBase + "novel/$id/reading?&q", headers = mapOf("referer" to mainUrl))
        val root = parseJson<NovelDetailResponse>(res.text)
        val novel = root.novel.firstOrNull() ?: return null

        val chapters = novel.volumes.flatMap { volume ->
            volume.chapters.map { c ->
                val chapterUrl = "$mainUrl/novelas/${novel.id}/${novel.slug}/${c.id}/${c.slug}"
                newChapterData(name = c.title ?: "Capítulo", url = chapterUrl) {
                    dateOfRelease = c.date
                }
            }
        }

        return newStreamResponse(
            name = novel.title ?: "Sin título",
            url = "$mainUrl/novelas/${novel.id}/${novel.slug}",
            data = chapters
        ) {
            author = novel.writer
            posterUrl = buildPoster(novel.image)
            synopsis = novel.content
            tags = novel.genres.mapNotNull { it.name }
            setStatus(novel.status)
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val chapterId = url.split("/").getOrNull(6) ?: return null
        val res = app.get("$apiBase/novel-chapter/$chapterId", headers = mapOf("referer" to mainUrl))
        val root = parseJson<ChapterResponse>(res.text)
        val content = root.chapter.firstOrNull()?.content ?: return null
        return Jsoup.parse(content.replace("\n", "<br>")).html()
    }
}
