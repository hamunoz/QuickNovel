package com.lagradost.quicknovel.providers

import android.util.Log
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrl
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus


class LightNovelTranslationsProvider : MainAPI() {
    override val name = "Light Novel Translations"
    override val mainUrl = "https://lightnovelstranslations.com/"
    // override val iconId = R.drawable.icon_book

    override val hasMainPage = false

    // Permite elegir orden y estado de la novela en la UI
    override val mainCategories = listOf(
        "Most Liked" to "most-liked",
        "Most Recent" to "most-recent"
    )

    override val tags = listOf(
        "All" to "all",
        "Ongoing" to "ongoing",
        "Completed" to "completed"
    )

    override val orderBys = emptyList<Pair<String, String>>()

    private fun log(tag: String, msg: String) =
        Log.d("LightNovelTranslations-$tag", msg)

    private suspend fun fetchDocument(url: String) = app.get(url).document

    /** Página principal con soporte para categorías y tags */
    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val category = mainCategory ?: "most-liked"
        val statusFilter = when (tag) {
            "ongoing" -> "&status=Ongoing"
            "completed" -> "&status=Completed"
            else -> ""
        }

        val url = "$mainUrl/read/page/$page?sortby=$category$statusFilter"
        log("loadMainPage", "Requesting $url")

        val document = fetchDocument(url)
        val novels = document.select("div.read_list-story-item").mapNotNull { el ->
            val link = el.selectFirst(".item_thumb a") ?: return@mapNotNull null
            val img = el.selectFirst(".item_thumb img")?.attr("src")
            val title = link.attr("title").orEmpty()
            val href = link.attr("href").orEmpty()

            newSearchResponse(name = title, url = href) {
                posterUrl = fixUrlNull(img)
            }
        }

        return HeadMainPageResponse(url, novels)
    }

    /** Carga información de la novela y lista de capítulos */
    override suspend fun load(url: String): LoadResponse {
        log("load", "Loading novel $url")
        val document = fetchDocument(url)

        val title = document.selectFirst("div.novel_title h3")?.text()?.trim().orEmpty()
        val author = document.selectFirst("div.novel_detail_info li:contains(Author)")
            ?.text()?.trim().orEmpty()
        val cover = document.selectFirst("div.novel-image img")?.attr("src")
        val statusText = document.selectFirst("div.novel_status")?.text()?.trim()

        val synopsis = try {
            val body2 = fetchDocument(url.replace("?tab=table_contents", ""))
            body2.selectFirst("div.novel_text p")?.text()?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        }

        val status = when (statusText) {
            "Ongoing" -> "Ongoing"
            "Hiatus" -> "On Hiatus"
            "Completed" -> "Completed"
            else -> null
        }

        val chapters = mutableListOf<ChapterData>()
        document.select("li.chapter-item.unlock").forEach { li ->
            val link = li.selectFirst("a") ?: return@forEach
            val chapterTitle = link.text().trim()
            val href = link.attr("href")

            chapters.add(
                newChapterData(
                    name = chapterTitle,
                    url = href
                )
            )
        }

        return newStreamResponse(title, fixUrl(url), chapters) {
            this.author = author
            this.posterUrl = fixUrlNull(cover)
            this.synopsis = synopsis
            setStatus(status)
        }
    }

    /** Carga el contenido del capítulo */
    override suspend fun loadHtml(url: String): String? {
        log("loadHtml", "Loading chapter $url")
        return try {
            val document = fetchDocument(url)
            val content = document.selectFirst("div.text_story")
            content?.select("div.ads_content")?.remove()
            content?.html()
        } catch (e: Exception) {
            Log.e("LightNovelTranslations", "Failed to load chapter: ${e.message}")
            null
        }
    }

    /** Búsqueda de novelas por nombre */
    override suspend fun search(query: String): List<SearchResponse> {
        log("search", "Searching for $query")
        if (query.isBlank()) return emptyList()

        val formData = mapOf("field-search" to query)
        val response = app.post("$mainUrl/read", data = formData)
        val document = response.document

        return document.select("div.read_list-story-item").mapNotNull { el ->
            val link = el.selectFirst(".item_thumb a") ?: return@mapNotNull null
            val img = el.selectFirst(".item_thumb img")?.attr("src")
            val title = link.attr("title").orEmpty()
            val href = link.attr("href").orEmpty()

            newSearchResponse(title, href) {
                posterUrl = fixUrlNull(img)
            }
        }
    }
}
