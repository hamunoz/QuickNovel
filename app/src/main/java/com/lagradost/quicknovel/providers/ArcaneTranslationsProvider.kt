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

class ArcaneTranslationsProvider : MainAPI() {
    override val name = "Arcane Translations"
    override val mainUrl = "https://arcanetranslations.com"
    override val iconId = R.drawable.icon_arcanetranslations

    override val hasMainPage = true

    override val mainCategories = listOf(
        "All" to "",
        "Completed" to "completed",
        "Ongoing" to "ongoing",
        "Hiatus" to "hiatus"
    )
    override val tags = listOf(
        "All" to "all",
        "Academy" to "academy",
        "Action" to "action",
        "Adventure" to "adventure",
        "Apocalypse" to "apocalypse",
        "Comedy" to "comedy",
        "Cyberpunk" to "cyberpunk",
        "Drama" to "drama",
        "Ecchi" to "ecchi",
        "Fantasy" to "fantasy",
        "Fusion" to "fusion",
        "Game World" to "game-world",
        "Harem" to "harem",
        "Martial Arts" to "martial-arts",
        "Medieval" to "medieval",
        "Misunderstandings" to "misunderstandings",
        "Modern" to "modern",
        "Mystery" to "mystery",
        "Obsession" to "obsession",
        "Possession" to "possession",
        "Psychological" to "psychological",
        "Regression" to "regression",
        "Regret" to "regret",
        "Reincarnation" to "reincarnation",
        "Romance" to "romance",
        "School Life" to "school-life",
        "Sci-Fi" to "sci-fi",
        "Seinen" to "seinen",
        "Shounen" to "shounen",
        "Slice of Life" to "slice-of-life",
        "Smut" to "smut",
        "Supernatural" to "supernatural",
        "Tragedy" to "tragedy",
        "Transmigration" to "transmigration",
        "War" to "war",
        "Wuxia" to "wuxia"
    )

    override val orderBys = listOf("Default" to "","New" to "latest", "Popular" to "popular", "Updates" to "update","Rating" to "rating","A-Z" to "title","Z-A" to "titlereverse")

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {

        var url = "$mainUrl/series/?page=${page}&genre%5B0%5D=${tag}&status=${mainCategory}&order=${orderBy}"
        if(tag=="all"){
            url = "$mainUrl/series/?page=${page}&status=${mainCategory}&order=${orderBy}"
        }
        val document = app.get(url, timeout = 60).document

        return HeadMainPageResponse(
            url,
            list = document.select("article.maindet").mapNotNull { article ->
                val linkElement = article.selectFirst("div.mdthumb a[title]") ?: return@mapNotNull null
                val href = linkElement.attr("href").ifBlank { return@mapNotNull null }

                val title = linkElement.attr("title").ifBlank {
                    article.selectFirst("h2[itemprop=headline] a")?.text()
                } ?: return@mapNotNull null

                val cover = article.selectFirst("div.mdthumb img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }

                val chapterText = article.selectFirst("div.mdinfodet span.nchapter")?.text() ?: ""
                val chapterCount = Regex("""\d+""").find(chapterText)?.value

                newSearchResponse(
                    name = title,
                    url = fixUrl(href)
                ) {
                    posterUrl = fixUrlNull(cover)
                    totalChapterCount = chapterCount
                }
            }
        )
    }


    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""

        val author = document.select("div.sertoauth .serl")
            .firstOrNull { it.selectFirst(".sername")?.text()?.contains("Artist", ignoreCase = true) == true }
            ?.selectFirst(".serval")?.text()?.trim() ?: ""

        val synopsis = document.selectFirst("div.sersys.entry-content")?.text()?.trim() ?: ""

        val cover = document.selectFirst("div.sertothumb img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        val status = document.selectFirst("div.sertostat span")?.text()?.trim() ?: ""



        val chapters = mutableListOf<ChapterData>()

        val chapterElements = document.select("div.eplister ul li")

        for (element in chapterElements) {
            val link = element.selectFirst("a") ?: continue
            val url = fixUrl(link.attr("href"))

            val numText = link.selectFirst("div.epl-num")?.text()?.trim() ?: ""
            val titleText = link.selectFirst("div.epl-title")?.text()?.trim() ?: numText

            // Skip if chapter is locked (contains 🔒)
            if ("🔒" in numText) continue

            chapters.add(
                newChapterData(
                    name = titleText,
                    url = url
                )
            )
        }

        chapters.reverse()




        return newStreamResponse(title,fixUrl(url), chapters) {
            this.author = author
            this.posterUrl = fixUrlNull(cover)
            this.synopsis = synopsis
            setStatus(status)
        }
    }


    override suspend fun loadHtml(url: String): String? {
        val fullUrl = fixUrl(url)
        Log.d("ArcaneTranslations", "Loading Chapter HTML from URL: $fullUrl")

        return try {
            val document = app.get(fullUrl).document

            // Main chapter content is in .epcontent
            val contentElement = document.selectFirst("div.epcontent")

            // Remove unnecessary elements but keep system notifications
            contentElement?.select(
                "script, noscript, iframe, img[src*=disable-blocker.jpg], " +
                        "a[href*=discord], p:matchesOwn(Translator:), p:matchesOwn(Translated By)"
            )?.forEach { it.remove() }

            val content = contentElement?.html()
            if (content != null) {
                Log.d("ArcaneTranslations", "Chapter Content Loaded Successfully")
                content
            } else {
                Log.e("ArcaneTranslations", "Chapter Content NOT FOUND")
                null
            }
        } catch (e: Exception) {
            Log.e("ArcaneTranslations", "Failed to load chapter HTML: ${e.message}")
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {


        var currentPage = 1
        var fetchedAll = false
        val results = mutableListOf<SearchResponse>()
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")

        while (!fetchedAll) {
            val url = "$mainUrl/page/$currentPage/?s=$encodedQuery"
            val document = app.get(url, timeout = 60).document

            val pageResults = document.select("article.maindet").mapNotNull { article ->
                val linkElement = article.selectFirst("div.mdthumb a[title]") ?: return@mapNotNull null
                val href = linkElement.attr("href").ifBlank { return@mapNotNull null }

                val title = linkElement.attr("title").ifBlank {
                    article.selectFirst("h2[itemprop=headline] a")?.text()
                } ?: return@mapNotNull null

                val cover = article.selectFirst("div.mdthumb img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }

                val chapterText = article.selectFirst("div.mdinfodet span.nchapter")?.text() ?: ""
                val chapterCount = Regex("""\d+""").find(chapterText)?.value

                newSearchResponse(
                    name = title,
                    url = fixUrl(href)
                ) {
                    posterUrl = fixUrlNull(cover)
                    totalChapterCount = chapterCount
                }
            }

            if (pageResults.isEmpty()) break

            results.addAll(pageResults)
            currentPage++
        }

        return results


    }


}