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
import okhttp3.FormBody
import okhttp3.Request

class FanMtlProvider : MainAPI() {
    override val name = "FanMtl"
    override val mainUrl = "https://www.fanmtl.com/"
    override val iconId = R.drawable.icon_wuxiabox // You might want to change this to a specific icon for FanMtl if available

    override val hasMainPage = true

    override val mainCategories = listOf(
        "All" to "all" // FanMtl seems to only have "all" category
    )

    override val tags = listOf(
        "All" to "all" // FanMtl doesn't seem to categorize by tags in the main catalog
    )

    override val orderBys = listOf(
        "New" to "newstime"
    )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val p = page + 1 // FanMtl pages are 1-indexed
        val url = "$mainUrl/list/all/all-newstime-$p.html" // Simplified based on FanMtl's structure
        val document = app.get(url).document

        return HeadMainPageResponse(
            url,
            list = document.select(".novel-item").mapNotNull { select ->
                val node = select.selectFirst("a[href][title]") ?: return@mapNotNull null
                val href = fixUrl(node.attr("href"))
                val title = node.attr("title") ?: return@mapNotNull null

                val cover = select.selectFirst("img[src][data-src]")?.attr("data-src")

                newSearchResponse(
                    name = title,
                    url = href
                ) {
                    posterUrl = fixUrlNull(cover)
                    // FanMtl main page doesn't provide chapter count easily
                }
            }
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.novel-title")?.text()?.trim() ?: ""
        val author = document.selectFirst(".author a")?.text()?.trim() ?: "" // Assuming author link
        val synopsis = document.selectFirst(".summary .content")?.text()?.trim() ?: ""

        val cover = document.selectFirst("img[src][data-src]")?.attr("data-src")

        // FanMtl doesn't seem to have an explicit status on the book page (Ongoing/Completed)
        val status = null

        val chapters = document.select("#chapters .chapter-list li").mapNotNull { li ->
            val link = li.selectFirst("a[href]") ?: return@mapNotNull null
            val chapterUrl = fixUrl(link.attr("href"))
            val chapterTitle = li.selectFirst(".chapter-title")?.text()?.trim().orEmpty()

            newChapterData(
                name = chapterTitle,
                url = chapterUrl,
            )
        }

        return newStreamResponse(title, fixUrl(url), chapters) {
            this.author = author
            this.posterUrl = fixUrlNull(cover)
            this.synopsis = synopsis
            setStatus(status)
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val fullUrl = fixUrl(url)
        Log.d("FanMtlProvider", "Loading Chapter HTML from URL: $fullUrl")

        return try {
            val document = app.get(fullUrl).document

            val contentElement = document.selectFirst(".chapter-content")

            // Remove unwanted elements
            contentElement?.select("script, div[align=center]")?.forEach { it.remove() }

            val content = contentElement?.html()
            if (content != null) {
                Log.d("FanMtlProvider", "Chapter Content Loaded Successfully")
                content
            } else {
                Log.e("FanMtlProvider", "Chapter Content NOT FOUND")
                null
            }
        } catch (e: Exception) {
            Log.e("FanMtlProvider", "Failed to load chapter HTML: ${e.message}")
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")

        val formBody = FormBody.Builder()
            .add("show", "title")
            .add("tempid", "1")
            .add("tbname", "news")
            .add("keyboard", encodedQuery)
            .build()

        val request = Request.Builder()
            .url("https://www.fanmtl.com/e/search/index.php")
            .post(formBody)
            .build()

        val document = app.get(request).document

        val pageResults = document.select(".novel-item").mapNotNull { element ->
            val node = element.selectFirst("a[href][title]") ?: return@mapNotNull null
            val href = fixUrl(node.attr("href"))
            val title = node.attr("title") ?: return@mapNotNull null

            val cover = element.selectFirst("img[src][data-src]")?.attr("data-src")

            newSearchResponse(title, href) {
                posterUrl = fixUrlNull(cover)
                // FanMtl search results don't provide chapter count easily
            }
        }
        results.addAll(pageResults)

        return results
    }
}
