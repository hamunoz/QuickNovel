package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import kotlin.math.roundToInt

class PawReadProver : MainAPI() {
    override val name = "PawRead"
    override val mainUrl = "https://pawread.com"
    override val iconId = R.drawable.pawread

    override val hasMainPage = true

    override val mainCategories = listOf(
        "All" to "all-",
        "Completed" to "wanjie-",
        "Ongoing" to "lianzai-",
        "Hiatus" to "hiatus-"
    )

    override val orderBys =
        listOf("Time updated" to "update", "Time Posted" to "post", "Clicks" to "click")

    override val tags = listOf(
        "Fantasy" to "Fantasy",
        "Action" to "Action",
        "Xuanhuan" to "Xuanhuan",
        "Romance" to "Romance",
        "Comedy" to "Comedy",
        "Mystery" to "Mystery",
        "Mature" to "Mature",
        "Harem" to "Harem",
        "Wuxia" to "Wuxia",
        "Xianxia" to "Xianxia",
        "Tragedy" to "Tragedy",
        "Sci-fi" to "Scifi",
        "Historical" to "Historical",
        "Ecchi" to "Ecchi",
        "Adventure" to "Adventure",
        "Adult" to "Adult",
        "Supernatural" to "Supernatural",
        "Psychological" to "Psychological",
        "Drama" to "Drama",
        "Horror" to "Horror",
        "Josei" to "Josei",
        "Mecha" to "Mecha",
        "Shounen" to "Shounen",
        "Smut" to "Smut",
        "Martial Arts" to "MartialArts",
        "School Life" to "SchoolLife",
        "Slice of Life" to "SliceofLife",
        "Gender Bender" to "GenderBender",
        "Sports" to "Sports",
        "Urban" to "Urban",
        "LitRPG" to "LitRPG",
        "Isekai" to "Isekai"
    )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        // Permitir paginación: agrega ?page=2, ?page=3, etc. según sea necesario
        val baseUrl = "$mainUrl/list/${mainCategory ?: "all-"}${tag ?: "All"}/${orderBy ?: "update"}/"
        val url = if (page > 0) "$baseUrl?page=$page" else baseUrl

        val document = app.get(url).document
        val items = document.select(".list-comic-thumbnail").mapNotNull { element ->
            val node = element.selectFirst(".caption>h3>a") ?: return@mapNotNull null
            val href = node.attr("href") ?: return@mapNotNull null
            newSearchResponse(
                name = node.text(),
                url = href
            ) {
                posterUrl = fixUrlNull(element.selectFirst(".image-link>img")?.attr("src"))
            }
        }
        return HeadMainPageResponse(url, list = items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?keywords=$query"
        val document = app.get(url).document
        return document.select(".list-comic-thumbnail").mapNotNull { element ->
            val node = element.selectFirst(".caption>h3>a") ?: return@mapNotNull null
            val href = node.attr("href") ?: return@mapNotNull null
            newSearchResponse(
                name = node.text(),
                url = href
            ) {
                posterUrl = fixUrlNull(element.selectFirst(".image-link>img")?.attr("src"))
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val board = document.selectFirst("#tab1_board") ?: throw ErrorLoadingException("Can't find novel info.")
        val regex = Regex("'(\\d+)'")
        val prefix = if (url.endsWith("/")) url else "$url/"

        val chapters = document.select(".item-box").toList().filter { element ->
            element.selectFirst("div>svg") == null
        }.mapNotNull { element ->
            val onclick = element.attr("onclick")
            val match = regex.find(onclick) ?: return@mapNotNull null
            val chapterId = match.groupValues[1]
            val chapterName = element.selectFirst("div>span.c_title")?.text() ?: return@mapNotNull null
            newChapterData(
                name = chapterName,
                url = "$prefix$chapterId.html"
            )
        }

        val info = document.select("#views_info>div")
        val novelName = board.selectFirst("div>h1")?.text() ?: throw ErrorLoadingException("No title found.")
        val author = info.getOrNull(3)?.text()
        val views = info.getOrNull(1)?.text()?.trim()?.removeSuffix("Views")?.trimEnd()?.toIntOrNull()
        val rating = document.select(".comic-score>span").getOrNull(1)?.text()?.toFloatOrNull()
            ?.let { (it * 1000.0 / 5.0).roundToInt() }
        val peopleVoted = document.selectFirst("#scoreCount")?.text()?.toIntOrNull()
        val tags = document.select(".tags").map { it.text().trim().removePrefix("#").trim() }
        val synopsis = document.selectFirst("#simple-des")?.text()
        val attr = board.selectFirst(">.col-md-3>div")
        val posterUrl = fixUrlNull(
            Regex("image:url\\((.*)\\)").find(attr?.attr("style") ?: "")?.groupValues?.get(1)
        )

        return newStreamResponse(
            name = novelName,
            url = url,
            data = chapters
        ) {
            this.views = views
            this.author = author
            this.rating = rating
            this.peopleVoted = peopleVoted
            this.tags = tags
            this.synopsis = synopsis
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val document = app.get(url).document
        val countdown = document.selectFirst("#countdown")
        if (countdown != null) {
            throw ErrorLoadingException("Not released, time until released ${countdown.text()}")
        }
        val html = document.selectFirst("#chapter_item")?.html() ?: throw ErrorLoadingException("No chapter found.")

        // Limpieza básica para evitar enlaces/promos indeseados
        val blockPatterns = listOf("pawread", "tinyurl", "bit.ly")
        val cleanedHtml = html.lines().filterNot { line ->
            blockPatterns.any { pattern -> line.contains(pattern, ignoreCase = true) }
        }.joinToString("\n")
        return cleanedHtml
    }
}
